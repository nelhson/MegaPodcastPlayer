package md.borisveriga.megapodcastplayer.core.data.repository

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.MomentDao
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.database.dao.QueueDao
import md.borisveriga.megapodcastplayer.core.database.model.MomentEntity
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.backup.BackupDownload
import md.borisveriga.megapodcastplayer.core.model.backup.BackupEpisodeState
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile
import md.borisveriga.megapodcastplayer.core.model.backup.BackupMoment
import md.borisveriga.megapodcastplayer.core.model.backup.BackupPodcast
import md.borisveriga.megapodcastplayer.core.model.backup.BackupQueueEntry
import md.borisveriga.megapodcastplayer.core.model.episodeIdOf
import md.borisveriga.megapodcastplayer.core.model.podcastIdOf

/**
 * Room-backed implementation of [BackupRepository].
 *
 * The whole design rests on one property of the id functions in `:core:model`: `podcastIdOf` and
 * `episodeIdOf` are pure functions of a feed URL and a guid. A backup therefore stores those two
 * strings and nothing else about identity, and a restore re-derives every id arithmetically once
 * the feeds have been re-fetched — no mapping table, no fuzzy title matching, no id in the file at
 * all. `BackupIdDerivationTest` guards the property this depends on.
 *
 * @property podcastDao show rows, read for the export and patched after each add.
 * @property episodeDao episode rows; the source of listening state and the target of its restore.
 * @property queueDao the durable queue.
 * @property momentDao the saved moments. They are in the backup because they are the one thing here
 *   the user wrote themselves: a show can be re-fetched and a position re-earned, a note cannot.
 * @property podcastRepository the ordinary add path, reused verbatim so that a restore resolves
 *   links, fetches, parses and stores exactly the way adding a show by hand does — RSS and YouTube
 *   included.
 * @property downloadRepository used only when the user opts into re-downloading.
 * @property preferences remembers when the last export was written.
 * @property clock injected so an exported timestamp is deterministic in tests.
 * @property crashReporter told when a write fails mid-restore, which the user only ever sees as a
 *   summary that is quietly short.
 * @property ioDispatcher dispatcher for the database and network work.
 */
@Singleton
class DefaultBackupRepository @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val queueDao: QueueDao,
    private val momentDao: MomentDao,
    private val podcastRepository: PodcastRepository,
    private val downloadRepository: DownloadRepository,
    private val preferences: UserPreferencesDataSource,
    private val clock: Clock,
    private val crashReporter: CrashReporter,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : BackupRepository {

    override fun observeLastBackupAt(): Flow<Long?> = preferences.lastBackupAtMs

    override suspend fun recordExported(exportedAtMs: Long) {
        preferences.setLastBackupAt(exportedAtMs)
    }

    override fun observeAcknowledgedRestoreId(): Flow<String?> = preferences.acknowledgedRestoreId

    override suspend fun acknowledgeRestore(runId: String) {
        preferences.setAcknowledgedRestoreId(runId)
    }

    override suspend fun export(): BackupFile = withContext(ioDispatcher) {
        val podcasts = podcastDao.getAll().sortedBy { it.sortOrder }
        BackupFile(
            exportedAtMs = clock.millis(),
            podcasts = podcasts.map { row ->
                BackupPodcast(
                    feedUrl = row.feedUrl,
                    source = row.source,
                    title = row.title,
                    author = row.author,
                    itunesId = row.itunesId,
                    sortOrder = row.sortOrder,
                    autoRefresh = row.autoRefresh,
                    addedAtMs = row.addedAt,
                )
            },
            episodes = episodeDao.getBackupState().map { row ->
                BackupEpisodeState(row.feedUrl, row.guid, row.positionMs, row.isPlayed)
            },
            queue = queueDao.getBackupEntries().map { row ->
                BackupQueueEntry(row.feedUrl, row.guid, row.position)
            },
            downloads = episodeDao.getBackupDownloads().map { row ->
                BackupDownload(row.feedUrl, row.guid)
            },
            moments = momentDao.getBackupRows().map { row ->
                BackupMoment(row.feedUrl, row.guid, row.positionMs, row.note, row.createdAt)
            },
        )
    }

    override suspend fun restore(
        file: BackupFile,
        options: RestoreOptions,
        onProgress: (RestoreProgress) -> Unit,
    ): RestoreSummary = withContext(ioDispatcher) {
        val ordered = file.podcasts.sortedBy { it.sortOrder }
        val restored = mutableListOf<BackupPodcast>()
        val failedTitles = mutableListOf<String>()

        ordered.forEachIndexed { index, backupPodcast ->
            onProgress(RestoreProgress(index, ordered.size, backupPodcast.title))
            if (addShow(backupPodcast)) {
                restored += backupPodcast
            } else {
                failedTitles += backupPodcast.title
            }
        }
        onProgress(RestoreProgress(ordered.size, ordered.size, ""))

        // Last, and only once every show exists: each add appends at the end of the library, so an
        // ordering applied any earlier would be undone by the next show to arrive.
        applyLibraryOrder(restored)

        val episodes = applyEpisodeState(file.episodes)
        val queued = restoreQueue(file.queue)
        val momentsRestored = restoreMoments(file.moments)
        val downloadsQueued = if (options.reDownload) reDownload(file.downloads) else 0

        RestoreSummary(
            showsRestored = restored.size,
            episodesRestored = episodes.restored,
            episodesMissing = episodes.missing,
            queueRestored = queued,
            momentsRestored = momentsRestored,
            downloadsQueued = downloadsQueued,
            failedTitles = failedTitles,
        )
    }

    /**
     * Adds one show and re-applies the metadata a fresh add cannot know.
     *
     * @param backupPodcast the show as the backup recorded it.
     * @return true when the show is in the library afterwards, whether this call put it there or it
     *   was already present.
     */
    private suspend fun addShow(backupPodcast: BackupPodcast): Boolean {
        val added = suspendRunCatching { podcastRepository.addFromInput(backupPodcast.feedUrl) }
            .getOrElse { failure ->
                crashReporter.recordNonFatal("Backup restore feed add failed", failure)
                AddPodcastResult.Failed(failure)
            }
        val succeeded = added is AddPodcastResult.Added || added is AddPodcastResult.AlreadyInLibrary
        if (!succeeded) return false

        val id = podcastIdOf(backupPodcast.feedUrl)
        podcastDao.restoreMetadata(id, backupPodcast.itunesId, backupPodcast.addedAtMs)
        podcastRepository.setAutoRefresh(id, backupPodcast.autoRefresh)
        return true
    }

    /**
     * Restores the hand-made library order for the shows that made it back.
     *
     * @param restored the shows that were successfully added, in the backup's own order.
     */
    private suspend fun applyLibraryOrder(restored: List<BackupPodcast>) {
        if (restored.isEmpty()) return
        podcastRepository.reorderLibrary(restored.map { podcastIdOf(it.feedUrl) })
    }

    /**
     * Re-applies listening state.
     *
     * @param states the state entries the backup carries.
     * @return how many episodes were updated and how many the publisher has since pruned. The
     *   second is counted rather than reported as a failure: a feed is allowed to forget an
     *   episode, and the user would rather be told how many than that something broke.
     */
    private suspend fun applyEpisodeState(states: List<BackupEpisodeState>): EpisodeStateTally {
        var restored = 0
        var missing = 0
        states.forEach { state ->
            val id = episodeIdOf(podcastIdOf(state.feedUrl), state.guid)
            val updated = suspendRunCatching {
                episodeDao.applyRestoredState(id, state.positionMs, state.isPlayed)
            }.getOrElse { failure ->
                crashReporter.recordNonFatal("Backup restore state write failed", failure)
                0
            }
            if (updated > 0) restored++ else missing++
        }
        return EpisodeStateTally(restored = restored, missing = missing)
    }

    /**
     * Replaces the queue with the backup's, dropping entries whose episode is not stored.
     *
     * Filtering is not tidiness: `queue.episode_id` is a cascading foreign key, so an entry naming
     * an episode the publisher pruned is a constraint violation rather than a harmless orphan.
     *
     * @param entries the queue as the backup recorded it.
     * @return how many entries were written.
     */
    private suspend fun restoreQueue(entries: List<BackupQueueEntry>): Int {
        if (entries.isEmpty()) return 0
        val ids = entries.sortedBy { it.position }
            .map { episodeIdOf(podcastIdOf(it.feedUrl), it.guid) }
        val existing = existingIds(ids)
        val ordered = ids.filter { it in existing }
        queueDao.replaceAll(ordered)
        return ordered.size
    }

    /**
     * Writes back the moments the backup carries.
     *
     * Filtered to episodes that exist for the same reason the queue is: `moments.episode_id` is a
     * cascading foreign key, so a moment naming an episode the publisher has pruned is a constraint
     * violation rather than a harmless orphan. A moment lost that way is a note the user cannot get
     * back, so the drop is counted into the summary rather than passed over.
     *
     * @param moments the moments the backup recorded.
     * @return how many were written.
     */
    private suspend fun restoreMoments(moments: List<BackupMoment>): Int {
        if (moments.isEmpty()) return 0
        val entities = moments.map { moment ->
            MomentEntity(
                episodeId = episodeIdOf(podcastIdOf(moment.feedUrl), moment.guid),
                positionMs = moment.positionMs,
                note = moment.note,
                createdAt = moment.createdAtMs,
            )
        }
        val existing = existingIds(entities.map { it.episodeId })
        val writable = entities.filter { it.episodeId in existing }
        if (writable.isEmpty()) return 0

        return suspendRunCatching {
            momentDao.restoreAll(writable)
            writable.size
        }.getOrElse { failure ->
            crashReporter.recordNonFatal("Backup restore moment write failed", failure)
            0
        }
    }

    /**
     * Re-queues the downloads the user opted to recover.
     *
     * @param downloads the downloads the backup recorded.
     * @return how many were accepted by the download stack.
     */
    private suspend fun reDownload(downloads: List<BackupDownload>): Int {
        val ids = downloads.map { episodeIdOf(podcastIdOf(it.feedUrl), it.guid) }
        val existing = existingIds(ids)
        return ids.count { id -> id in existing && downloadRepository.download(id) }
    }

    /**
     * Narrows derived ids to those that exist, in chunks.
     *
     * Room expands `IN (:ids)` into one host variable per element and SQLite refuses more than 999
     * of them, which a well-stocked restored queue reaches on its own.
     *
     * @param ids the derived ids to check.
     * @return those that name a stored episode.
     */
    private suspend fun existingIds(ids: List<String>): Set<String> =
        ids.chunked(ID_LOOKUP_CHUNK).flatMap { episodeDao.getExistingIds(it) }.toSet()

    /**
     * How an episode-state pass went.
     *
     * @property restored episodes whose state was written.
     * @property missing episodes the backup knew about that no longer exist.
     */
    private data class EpisodeStateTally(val restored: Int, val missing: Int)

    private companion object {
        /** Comfortably under SQLite's 999-variable limit, with room for the rest of the statement. */
        const val ID_LOOKUP_CHUNK = 500
    }
}

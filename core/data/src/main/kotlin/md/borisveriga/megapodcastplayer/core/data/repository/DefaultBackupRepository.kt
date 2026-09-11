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
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile
import md.borisveriga.megapodcastplayer.core.model.backup.BackupPodcast
import md.borisveriga.megapodcastplayer.core.model.podcastIdOf

/**
 * Room-backed implementation of [BackupRepository].
 *
 * A show is a link, so an export is a list of links and a restore is a list of adds. The one thing
 * that makes that arithmetic rather than matching is `podcastIdOf`: it is a pure function of the
 * feed URL, so a re-added feed lands on byte-identical rows with no mapping table and no fuzzy
 * title matching. `BackupIdDerivationTest` guards the property this depends on.
 *
 * @property podcastDao show rows, read for the export and reordered after the adds.
 * @property podcastRepository the ordinary add path, reused verbatim so that a restore resolves
 *   links, fetches, parses and stores exactly the way adding a show by hand does — RSS and YouTube
 *   included.
 * @property preferences remembers when the last export was written.
 * @property clock injected so an exported timestamp is deterministic in tests.
 * @property crashReporter told when a feed cannot be added, which the user only ever sees as a
 *   name in the list of shows that did not arrive.
 * @property ioDispatcher dispatcher for the database and network work.
 */
@Singleton
class DefaultBackupRepository @Inject constructor(
    private val podcastDao: PodcastDao,
    private val podcastRepository: PodcastRepository,
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
        BackupFile(
            exportedAtMs = clock.millis(),
            podcasts = podcastDao.getAll().sortedBy { it.sortOrder }.map { row ->
                BackupPodcast(
                    feedUrl = row.feedUrl,
                    source = row.source,
                    title = row.title,
                    sortOrder = row.sortOrder,
                )
            },
        )
    }

    override suspend fun restore(
        file: BackupFile,
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

        RestoreSummary(showsRestored = restored.size, failedTitles = failedTitles)
    }

    /**
     * Adds one show.
     *
     * @param backupPodcast the show as the document recorded it.
     * @return true when the show is in the library afterwards, whether this call put it there or it
     *   was already present.
     */
    private suspend fun addShow(backupPodcast: BackupPodcast): Boolean {
        val added = suspendRunCatching { podcastRepository.addFromInput(backupPodcast.feedUrl) }
            .getOrElse { failure ->
                crashReporter.recordNonFatal("Subscription import feed add failed", failure)
                AddPodcastResult.Failed(failure)
            }
        return added is AddPodcastResult.Added || added is AddPodcastResult.AlreadyInLibrary
    }

    /**
     * Restores the hand-made library order for the shows that made it back.
     *
     * @param restored the shows that were successfully added, in the document's own order.
     */
    private suspend fun applyLibraryOrder(restored: List<BackupPodcast>) {
        if (restored.isEmpty()) return
        podcastRepository.reorderLibrary(restored.map { podcastIdOf(it.feedUrl) })
    }
}

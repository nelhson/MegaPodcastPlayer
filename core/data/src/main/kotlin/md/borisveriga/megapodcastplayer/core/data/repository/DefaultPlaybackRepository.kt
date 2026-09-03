package md.borisveriga.megapodcastplayer.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.data.mapper.asPlayableEpisode
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.QueueDao
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackProgressRecorder
import md.borisveriga.megapodcastplayer.core.media.PlaybackQueueSource
import md.borisveriga.megapodcastplayer.core.media.download.EpisodeDownloader
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

/**
 * Room- and DataStore-backed implementation of everything the player persists.
 *
 * One class implements all three interfaces because they are three views of the same table pair —
 * [PlaybackRepository] is what the UI reads, [PlaybackQueueSource] is what the service reads, and
 * [PlaybackProgressRecorder] is what the service writes — and splitting them would mean three
 * classes holding the same two DAOs.
 *
 * @property queueDao the durable queue.
 * @property episodeDao episode rows, including the user-owned position and played columns.
 * @property userPreferences playback speed, skip intervals and the last-played episode.
 * @property downloader used to free an episode's audio once it has been played, when the user
 *   has asked for that.
 * @property ioDispatcher dispatcher for the database work.
 */
@Singleton
class DefaultPlaybackRepository @Inject constructor(
    private val queueDao: QueueDao,
    private val episodeDao: EpisodeDao,
    private val userPreferences: UserPreferencesDataSource,
    private val downloader: EpisodeDownloader,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : PlaybackRepository, PlaybackQueueSource, PlaybackProgressRecorder {

    override fun observeQueue(): Flow<List<PlayableEpisode>> =
        queueDao.observeQueuedWithShow().map { rows -> rows.map { it.asPlayableEpisode() } }

    override fun observeLastPlayedEpisodeId(): Flow<String?> =
        userPreferences.lastPlayedEpisodeId

    override fun observePlaybackSettings(): Flow<PlaybackSettings> =
        userPreferences.playbackSettings

    override suspend fun playableEpisode(episodeId: String): PlayableEpisode? =
        withContext(ioDispatcher) {
            episodeDao.getWithShowByIds(listOf(episodeId)).firstOrNull()?.asPlayableEpisode()
        }

    override suspend fun enqueue(episodeId: String) = withContext(ioDispatcher) {
        queueDao.enqueue(episodeId)
    }

    override suspend fun dequeue(episodeId: String) = withContext(ioDispatcher) {
        queueDao.remove(episodeId)
    }

    // Same statement as recordQueue below, deliberately not shared: one is the UI asking for a
    // reorder and the other is the service reporting one, and collapsing them would make a change
    // to either side silently change the other.
    override suspend fun reorderQueue(episodeIds: List<String>) = withContext(ioDispatcher) {
        queueDao.replaceAll(episodeIds)
    }

    override suspend fun setPlayed(episodeId: String, isPlayed: Boolean, positionMs: Long) =
        withContext(ioDispatcher) {
            // Finishing an episode resets its position, which is what the caller's default asks
            // for; an undo passes back the position it saved before the mark.
            episodeDao.setPlayed(id = episodeId, isPlayed = isPlayed, positionMs = positionMs)
        }

    override suspend fun setSpeed(speed: Float) = userPreferences.setSpeed(speed)

    override suspend fun setSkipIntervals(forwardMs: Long, backMs: Long) =
        userPreferences.setSkipIntervals(forwardMs = forwardMs, backMs = backMs)

    override suspend fun setAutoPlayNext(enabled: Boolean) =
        userPreferences.setAutoPlayNext(enabled)

    override suspend fun resumableQueue(): List<PlayableEpisode> = withContext(ioDispatcher) {
        val queued = queueDao.getQueuedWithShow().map { it.asPlayableEpisode() }
        if (queued.isNotEmpty()) return@withContext queued

        // Nothing queued: the user was playing a single episode straight from a show's list, so
        // resume that rather than telling the system there is nothing to resume.
        val lastPlayedId = userPreferences.lastPlayedEpisodeId.first()
            ?: return@withContext emptyList()
        listOfNotNull(
            episodeDao.getWithShowByIds(listOf(lastPlayedId)).firstOrNull()?.asPlayableEpisode(),
        )
    }

    override suspend fun playableEpisodes(episodeIds: List<String>): List<PlayableEpisode> =
        withContext(ioDispatcher) {
            if (episodeIds.isEmpty()) return@withContext emptyList()
            val byId = episodeDao.getWithShowByIds(episodeIds)
                .associateBy { it.episode.id }
            // `IN (:ids)` returns rows in whatever order SQLite likes, so the caller's order — which
            // *is* the play order — is reapplied here. Ids with no row are dropped: a queue entry
            // for a show the user has since removed is stale, not an error.
            episodeIds.mapNotNull { id -> byId[id]?.asPlayableEpisode() }
        }

    override suspend fun recordPosition(episodeId: String, positionMs: Long, durationMs: Long?) {
        withContext(ioDispatcher) {
            episodeDao.updatePosition(id = episodeId, positionMs = positionMs)
            // Many feeds omit itunes:duration entirely; the decoder's measurement is the only
            // duration those episodes will ever have.
            durationMs?.let { episodeDao.fillMissingDuration(id = episodeId, durationMs = it) }
        }
    }

    override suspend fun recordCompleted(episodeId: String) = withContext(ioDispatcher) {
        episodeDao.setPlayed(id = episodeId, isPlayed = true, positionMs = 0L)
        // A finished episode has no business sitting at the top of "up next".
        queueDao.remove(episodeId)
        deleteAudioIfRequested(episodeId)
    }

    /**
     * Frees a finished episode's audio when the user has asked for that.
     *
     * Only touches episodes that are actually downloaded, so this is a cheap no-op for the usual
     * case of an episode that was streamed. The row's download columns are updated by the removal
     * event, not here.
     */
    private suspend fun deleteAudioIfRequested(episodeId: String) {
        if (!userPreferences.downloadSettings.first().deleteAfterPlaying) return
        if (episodeDao.getDownloadState(episodeId) != DownloadState.COMPLETED) return
        // The player is what called us, so the app is in the foreground — but not necessarily
        // interactively, and a removal is not worth risking a foreground-service refusal over.
        downloader.remove(episodeId, foreground = false)
    }

    override suspend fun recordQueue(episodeIds: List<String>) = withContext(ioDispatcher) {
        queueDao.replaceAll(episodeIds)
    }
}

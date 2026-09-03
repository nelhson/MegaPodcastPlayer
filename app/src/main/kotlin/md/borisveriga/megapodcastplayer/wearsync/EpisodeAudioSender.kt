package md.borisveriga.megapodcastplayer.wearsync

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.download.DownloadedAudio
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearPaths

/**
 * Sends a downloaded episode's audio to the watch, over a Data Layer channel.
 *
 * This is the phone's half of taking an episode on a run without it. The watch asks with
 * [md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand.CopyToWatch]; everything after that happens
 * here, on the application scope, because the transfer outlives the message that started it by
 * minutes — Bluetooth moves an episode at roughly a megabyte every few seconds.
 *
 * Only completed downloads are sent, and only from the cache: see [DownloadedAudio] for why a gap
 * fails the transfer rather than quietly fetching the rest over mobile data.
 *
 * There is no progress reported back. The watch counts the bytes it receives, which is the number
 * that matters to the person watching a bar move, and one fewer thing on the link.
 *
 * @property playbackRepository resolves an episode id into its audio URL and download state.
 * @property downloadedAudio reads the bytes back out of the download cache.
 * @property channelClient opens the channel to the watch.
 * @property scope application scope: a transfer must survive the screen, and the process being
 *   backgrounded, but not the process dying — a half-sent episode is discarded by the watch.
 */
@Singleton
internal class EpisodeAudioSender @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val downloadedAudio: DownloadedAudio,
    private val channelClient: ChannelClient,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Guards [inFlight]; the commands that mutate it arrive on Play Services' callback threads. */
    private val mutex = Mutex()

    /**
     * Episodes already on their way to a watch, and the jobs sending them.
     *
     * A watch whose app was killed mid-transfer asks again on its next open, and the user can tap a
     * row twice. Either would otherwise open a second channel for the same episode and interleave
     * two copies of it into one file.
     *
     * The jobs are kept, rather than only the ids, so that [cancel] has something to stop: a watch
     * that has given up on an episode is the one case where the phone should abandon a copy it has
     * already started.
     */
    private val inFlight = mutableMapOf<String, Job>()

    /**
     * Starts sending an episode, if it is not already going.
     *
     * @param nodeId the watch that asked.
     * @param episodeId the episode it asked for.
     * @return the job doing the work, or null when that episode is already being sent.
     */
    suspend fun send(nodeId: String, episodeId: String): Job? = mutex.withLock {
        if (inFlight.containsKey(episodeId)) return@withLock null

        // Launched under the lock, and the job recorded before it is released: the coroutine's own
        // finally takes the same lock, so a transfer that finishes immediately waits to be recorded
        // before it removes itself rather than leaving a dead job behind in the map.
        val job = scope.launch {
            try {
                transfer(nodeId, episodeId)
            } finally {
                // In a finally: a cancelled or failed transfer must leave the episode askable
                // again, or one dropped Bluetooth connection would make it permanently unsendable.
                mutex.withLock { inFlight.remove(episodeId) }
            }
        }
        inFlight[episodeId] = job
        job
    }

    /**
     * Stops sending an episode, if it is going.
     *
     * Cancellation reaches the copy between blocks — see [DownloadedAudio.copyTo] — so the phone
     * stops within one 64 KB write rather than at the end of the episode. The channel is closed on
     * the way out of [transfer], which is what tells the watch the stream has ended; the watch has
     * already thrown its partial file away by then.
     *
     * Asking for an episode that is not being sent does nothing, which is the ordinary case when the
     * transfer finished in the time the watch's message took to arrive.
     *
     * @param episodeId the episode to abandon.
     */
    suspend fun cancel(episodeId: String) {
        mutex.withLock { inFlight[episodeId] }?.cancel()
    }

    /**
     * Opens a channel and pours one episode down it.
     *
     * The stream is closed on the way out whatever happened, which is what tells the watch the
     * transfer has ended. A short file is not flagged here: the watch knows how many bytes to expect
     * from the published library and discards anything shorter, which is a check that also catches
     * the case this side cannot see — a link that dropped mid-copy.
     *
     * "Whatever happened" includes being cancelled, which is why the close sits in a `finally` and
     * runs [NonCancellable]. A cancelled copy that left the channel open would be the worst of both:
     * the phone stops sending, and the watch waits on a stream that never ends.
     */
    private suspend fun transfer(nodeId: String, episodeId: String) {
        val episode = playbackRepository.playableEpisode(episodeId)?.episode
        if (episode == null) {
            Log.w(TAG, "The watch asked for an episode this phone does not have: $episodeId")
            return
        }
        if (episode.downloadState != DownloadState.COMPLETED) {
            Log.w(TAG, "The watch asked for $episodeId, which is not downloaded")
            return
        }

        val channel = suspendRunCatching {
            channelClient.openChannel(nodeId, WearPaths.episodeAudioPath(episodeId)).await()
        }.getOrNull() ?: run {
            Log.w(TAG, "Could not open a channel to $nodeId")
            return
        }

        try {
            val written = suspendRunCatching {
                val stream = channelClient.getOutputStream(channel).await()
                stream.use { downloadedAudio.copyTo(episode.audioUrl, it) }
            }.getOrNull()

            if (written == null) {
                Log.w(TAG, "Sending $episodeId to the watch failed part way")
            }
        } finally {
            withContext(NonCancellable) {
                suspendRunCatching { channelClient.close(channel).await() }
            }
        }
    }

    private companion object {
        const val TAG = "EpisodeAudioSender"
    }
}

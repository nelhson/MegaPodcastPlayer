package md.borisveriga.bpodcat.wearsync

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import md.borisveriga.bpodcat.core.common.di.ApplicationScope
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.media.download.DownloadedAudio
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.wearprotocol.WearPaths

/**
 * Sends a downloaded episode's audio to the watch, over a Data Layer channel.
 *
 * This is the phone's half of taking an episode on a run without it. The watch asks with
 * [md.borisveriga.bpodcat.core.wearprotocol.WearCommand.CopyToWatch]; everything after that happens
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
     * Episodes already on their way to a watch.
     *
     * A watch whose app was killed mid-transfer asks again on its next open, and the user can tap a
     * row twice. Either would otherwise open a second channel for the same episode and interleave
     * two copies of it into one file.
     */
    private val inFlight = mutableSetOf<String>()

    /**
     * Starts sending an episode, if it is not already going.
     *
     * @param nodeId the watch that asked.
     * @param episodeId the episode it asked for.
     * @return the job doing the work, or null when that episode is already being sent.
     */
    suspend fun send(nodeId: String, episodeId: String): Job? {
        val started = mutex.withLock { inFlight.add(episodeId) }
        if (!started) return null

        return scope.launch {
            try {
                transfer(nodeId, episodeId)
            } finally {
                // In a finally: a cancelled or failed transfer must leave the episode askable
                // again, or one dropped Bluetooth connection would make it permanently unsendable.
                mutex.withLock { inFlight.remove(episodeId) }
            }
        }
    }

    /**
     * Opens a channel and pours one episode down it.
     *
     * The stream is closed on the way out whatever happened, which is what tells the watch the
     * transfer has ended. A short file is not flagged here: the watch knows how many bytes to expect
     * from the published library and discards anything shorter, which is a check that also catches
     * the case this side cannot see — a link that dropped mid-copy.
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

        val written = suspendRunCatching {
            val stream = channelClient.getOutputStream(channel).await()
            stream.use { downloadedAudio.copyTo(episode.audioUrl, it) }
        }.getOrNull()

        if (written == null) {
            Log.w(TAG, "Sending $episodeId to the watch failed part way")
        }
        suspendRunCatching { channelClient.close(channel).await() }
    }

    private companion object {
        const val TAG = "EpisodeAudioSender"
    }
}

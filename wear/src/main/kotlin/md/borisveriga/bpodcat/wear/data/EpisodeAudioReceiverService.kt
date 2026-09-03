package md.borisveriga.bpodcat.wear.data

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.wearprotocol.OfflineEpisode
import md.borisveriga.bpodcat.core.wearprotocol.WearPaths
import md.borisveriga.bpodcat.wear.R

/**
 * Receives an episode's audio from the phone.
 *
 * Play Services opens the channel and starts this service — starting the watch app's process if it
 * is not running — so a transfer survives the app being closed, which for a file that takes minutes
 * it has to.
 *
 * The copy runs *inside* the callback, on the background thread Play Services calls it on. That is
 * the contract this API is built around: the service is kept alive for as long as the callback has
 * not returned, so returning early to do the work elsewhere is exactly how a transfer gets killed
 * half way. Hence the [runBlocking] — the one place in this app where it is the right answer rather
 * than a shortcut.
 *
 * @property store where the bytes land and what remembers them.
 * @property library the phone's published offer, which is where the title and the expected size come
 *   from — the channel carries only audio and an id.
 * @property channelClient opens the stream for the channel handed to the callback.
 */
@AndroidEntryPoint
class EpisodeAudioReceiverService : WearableListenerService() {

    @Inject
    lateinit var store: WatchEpisodeStore

    @Inject
    lateinit var library: WatchLibrary

    @Inject
    lateinit var channelClient: ChannelClient

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val episodeId = WearPaths.episodeIdFromAudioPath(channel.path) ?: return

        runBlocking {
            // The index has to be current before anything is added to it, and this service may be
            // what started the process — in which case nothing has read it yet.
            store.load()

            // The offer is what carries the title and the expected size; the channel carries
            // only audio and an id. Its absence means the published list has been replaced since
            // the watch asked, which is rare and not worth refusing a transfer over.
            val offer = library.cached().episodes.firstOrNull { it.id == episodeId }
                ?: OfflineEpisode(id = episodeId, title = getString(R.string.watch_unknown_episode))

            val received = suspendRunCatching {
                val stream = channelClient.getInputStream(channel).await()
                stream.use { store.receive(offer, it) }
            }.getOrElse { error ->
                Log.w(TAG, "Could not read the audio channel for $episodeId", error)
                false
            }

            if (!received) Log.w(TAG, "The transfer of $episodeId did not complete")
            suspendRunCatching { channelClient.close(channel).await() }
        }
    }

    private companion object {
        const val TAG = "EpisodeAudioReceiver"
    }
}

package md.borisveriga.megapodcastplayer.wearsync

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts a copy of an episode's audio to the watch.
 *
 * An interface with one implementation, for two reasons. It keeps [WearCommandExecutor] free of
 * `Context` and of service intents, which is what lets every other command be unit-tested in the
 * same file; and it names the thing the executor actually wants — "get this episode moving" —
 * rather than the mechanism that currently achieves it.
 */
internal interface EpisodeAudioTransfers {

    /**
     * Begins sending one episode, returning as soon as it is under way rather than when it lands.
     *
     * @param nodeId the watch that asked.
     * @param episodeId the episode it asked for.
     */
    suspend fun start(nodeId: String, episodeId: String)

    /**
     * Abandons a copy that is under way, if it still is.
     *
     * No node id: an episode is sent to one watch at a time, and the watch that asked to stop is by
     * definition the one receiving it.
     *
     * @param episodeId the episode to stop sending.
     */
    suspend fun cancel(episodeId: String)
}

/**
 * Runs transfers inside a foreground service, so the phone keeps working on them once it is pocketed.
 *
 * **Why a service at all.** The copy itself lives in [EpisodeAudioSender] on the application scope,
 * which outlives the Data Layer callback that started it — but a coroutine is not a reason for
 * Android to keep the process running well. Once [WearCommandService] is destroyed the app has no
 * component at all: it becomes a *cached* process, scheduled in the background cgroup and, from
 * Android 12 on, eligible for the app freezer within seconds. A transfer that already takes minutes
 * over Bluetooth then takes far longer, or stops until the app is opened again. A foreground service
 * is what keeps the process at foreground priority for the length of the copy.
 *
 * **Why it can fall back.** From Android 12 a background app may not start a foreground service
 * unless something exempts it, and what exempts this one — Play Services having just started
 * [WearCommandService] to deliver the watch's message — is a window that has to still be open. When
 * it is not, the start throws and the transfer runs the old way rather than not at all: slower than
 * it should be is a great deal better than a tap that does nothing.
 *
 * @property context application context; used only to start the service.
 * @property sender the fallback path, and the same object the service delegates to.
 */
@Singleton
internal class ForegroundEpisodeAudioTransfers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sender: EpisodeAudioSender,
) : EpisodeAudioTransfers {

    override suspend fun start(nodeId: String, episodeId: String) {
        if (startService(nodeId, episodeId)) return
        sender.send(nodeId, episodeId)
    }

    /**
     * Stops a transfer, without going near the service.
     *
     * The service exists to keep the process alive while a copy runs; stopping one is the opposite
     * errand and takes no time at all. It also stops itself: cancelling the transfer completes the
     * job it is waiting on, and the last one to finish takes the service down.
     */
    override suspend fun cancel(episodeId: String) = sender.cancel(episodeId)

    /**
     * Asks the platform to start [EpisodeTransferService].
     *
     * @return true when the service was started; false when the platform refused, which is not an
     *   error so much as a fact about when the watch asked.
     */
    private fun startService(nodeId: String, episodeId: String): Boolean = try {
        ContextCompat.startForegroundService(
            context,
            EpisodeTransferService.intent(context, nodeId = nodeId, episodeId = episodeId),
        )
        true
    } catch (error: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException, which is an IllegalStateException, and the
        // plain background-service-start refusal that older releases throw in its place.
        Log.w(TAG, "Could not start the transfer service; copying without one", error)
        false
    }

    private companion object {
        const val TAG = "EpisodeAudioTransfers"
    }
}

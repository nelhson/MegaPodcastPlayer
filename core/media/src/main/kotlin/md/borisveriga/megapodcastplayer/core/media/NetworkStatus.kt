package md.borisveriga.megapodcastplayer.core.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Says whether the device has a network to fetch from, asked at the moment it matters.
 *
 * Exists for one decision: whether to ask for an episode's picture. The video flavour of an episode
 * is one merged source, so a picture that cannot be fetched stops the sound with it until the
 * player gives up and falls back. That was a fair price on the video screen, where the user had
 * just asked to watch. It is not one for the collapsed bar, which asks on its own every time the app
 * comes to the front: offline, with the audio downloaded, each of those would interrupt an episode
 * that was playing perfectly well.
 *
 * A question rather than a flow. Nothing here needs to be told when the network returns — the next
 * ask is the next time the app comes forward, the episode changes or the video screen opens — so
 * there is no callback to register and none to forget to unregister.
 *
 * @property context used to reach the connectivity service.
 */
@Singleton
class NetworkStatus @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Whether the active network claims a route to the internet.
     *
     * The claim, not a validated connection: a network that is still being checked, or sits behind
     * a sign-in page, is worth trying, and the player's own fallback covers the try that fails.
     *
     * @return false when there is no active network, or it has no internet capability; true when
     *   the service cannot be asked at all, because not knowing is no reason to withhold a picture.
     */
    fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

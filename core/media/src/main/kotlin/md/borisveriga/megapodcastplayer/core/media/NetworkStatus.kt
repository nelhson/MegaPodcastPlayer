package md.borisveriga.megapodcastplayer.core.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

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
 * A question for that decision, rather than a flow. Nothing there needs to be told when the network
 * returns — the next ask is the next time the app comes forward, the episode changes or the video
 * screen opens — so there is no callback to register and none to forget to unregister.
 *
 * And a flow, [observeOnline], for the one place that draws the answer: a *Play video* button that
 * says it cannot play has to stop saying so when the network comes back, with nobody asking.
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

    /**
     * The answer to [isOnline], followed for as long as it is collected.
     *
     * Starts with the answer as it stands, then follows the default network: its capabilities
     * changing, and its being lost. The callback is registered when collection starts and taken
     * back when it stops, so a screen that is not showing costs nothing.
     *
     * @return a flow of whether there is a network to fetch from, without repeats; a single `true`
     *   when the connectivity service cannot be asked, for the reason [isOnline] gives.
     */
    fun observeOnline(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        send(isOnline())
        manager?.registerDefaultNetworkCallback(callback)
        awaitClose { manager?.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

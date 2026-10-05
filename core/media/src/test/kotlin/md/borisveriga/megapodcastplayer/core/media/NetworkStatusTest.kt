package md.borisveriga.megapodcastplayer.core.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Tests for [NetworkStatus]: the answer that decides whether a picture is asked for at all.
 *
 * Both wrong answers cost something. "Online" with no network asks for a picture that cannot arrive
 * and takes the sound down with it; "offline" with one withholds a picture that would have played.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val status = NetworkStatus(context)

    /**
     * Gives the active network exactly [capabilities].
     *
     * @param capabilities the `NET_CAPABILITY_*` constants the network claims.
     */
    private fun activeNetworkHas(vararg capabilities: Int) {
        val claimed = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(claimed).addCapability(it) }
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, claimed)
    }

    @Test
    fun `a network that claims the internet is online`() {
        activeNetworkHas(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        assertTrue(status.isOnline())
    }

    @Test
    fun `a network with no route to the internet is not`() {
        // Connected to something — a watch, a car — that carries nothing a picture could come over.
        activeNetworkHas(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        assertFalse(status.isOnline())
    }

    @Test
    fun `no active network at all is offline`() {
        // Aeroplane mode: the case the gate exists for.
        shadowOf(manager).setDefaultNetworkActive(false)
        shadowOf(manager).setActiveNetworkInfo(null)

        assertFalse(status.isOnline())
    }

    @Test
    fun `the followed answer starts as it stands and follows the network going and back`() = runTest {
        activeNetworkHas(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val seen = mutableListOf<Boolean>()
        val following = launch(UnconfinedTestDispatcher(testScheduler)) {
            status.observeOnline().collect { seen += it }
        }
        val callback = shadowOf(manager).networkCallbacks.single()
        val network = checkNotNull(manager.activeNetwork)

        callback.onLost(network)
        // Said twice by the platform, drawn once.
        callback.onLost(network)
        val back = ShadowNetworkCapabilities.newInstance()
        shadowOf(back).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        callback.onCapabilitiesChanged(network, back)

        assertEquals(listOf(true, false, true), seen)
        following.cancel()
    }

    @Test
    fun `the callback is taken back when nobody follows any more`() = runTest {
        val following = launch(UnconfinedTestDispatcher(testScheduler)) {
            status.observeOnline().collect {}
        }
        assertEquals(1, shadowOf(manager).networkCallbacks.size)

        following.cancel()
        runCurrent()

        assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
    }
}

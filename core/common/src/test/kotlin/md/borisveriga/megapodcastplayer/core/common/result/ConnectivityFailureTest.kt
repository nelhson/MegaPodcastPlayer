package md.borisveriga.megapodcastplayer.core.common.result

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [isConnectivityFailure].
 *
 * The line it draws decides what reaches Crashlytics, so both sides are pinned: a device with no
 * network must not look like a bug, and a server that answers wrongly must not look like a device
 * with no network.
 */
class ConnectivityFailureTest {

    @Test
    fun `a failed DNS lookup is a connectivity failure`() {
        // The reported case: a backgrounded refresh, cut off by the system, failing every feed.
        val failure = UnknownHostException(
            "Unable to resolve host \"feeds.megaphone.fm\": No address associated with hostname",
        )

        assertTrue(failure.isConnectivityFailure)
    }

    @Test
    fun `a connection torn down mid-read is a connectivity failure`() {
        assertTrue(SocketException("Software caused connection abort").isConnectivityFailure)
    }

    @Test
    fun `a refused connection is a connectivity failure`() {
        assertTrue(ConnectException("Failed to connect").isConnectivityFailure)
    }

    @Test
    fun `a timeout is a connectivity failure`() {
        assertTrue(SocketTimeoutException("timeout").isConnectivityFailure)
    }

    @Test
    fun `a wrapped connectivity failure is still one`() {
        val wrapped = IllegalStateException("parse failed", IOException(UnknownHostException("x")))

        assertTrue(wrapped.isConnectivityFailure)
    }

    @Test
    fun `an http error status is not a connectivity failure`() {
        // FeedRemoteDataSource's own spelling of a non-2xx answer: the server was reachable.
        val failure = IOException("Feed request failed with HTTP 500 for https://example.com/feed")

        assertFalse(failure.isConnectivityFailure)
    }

    @Test
    fun `a certificate problem is not a connectivity failure`() {
        assertFalse(SSLHandshakeException("Chain validation failed").isConnectivityFailure)
    }

    @Test
    fun `an ordinary bug is not a connectivity failure`() {
        assertFalse(IllegalArgumentException("Unexpected JSON token").isConnectivityFailure)
    }

    @Test
    fun `a cause cycle ends rather than looping forever`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertFalse(first.isConnectivityFailure)
    }
}

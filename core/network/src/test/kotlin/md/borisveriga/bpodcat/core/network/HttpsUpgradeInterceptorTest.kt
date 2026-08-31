package md.borisveriga.bpodcat.core.network

import java.io.IOException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests the cleartext-avoidance policy.
 *
 * Driven through a real [OkHttpClient] whose last interceptor answers from memory instead of
 * calling through, so the interceptor runs in the chain it will actually run in — ordering,
 * retries and all — while nothing leaves the machine. A [MockWebServer][mockwebserver3.MockWebServer]
 * would not do here: it listens on an ephemeral port, and a non-default port is precisely the case
 * this interceptor declines to upgrade.
 *
 * What each test asserts is what was *attempted*, in order. A request that succeeds proves nothing
 * on its own — the bug being fixed is about which scheme went out on the wire.
 */
class HttpsUpgradeInterceptorTest {

    @Test
    fun `upgrades a cleartext GET to https`() {
        val terminal = RecordingInterceptor()

        clientWith(terminal).get("http://dts.podtrac.com/redirect.mp3/example.com/ep1.mp3")

        assertEquals(
            listOf("https://dts.podtrac.com/redirect.mp3/example.com/ep1.mp3"),
            terminal.attempted,
        )
    }

    @Test
    fun `leaves an https request untouched`() {
        val terminal = RecordingInterceptor()

        clientWith(terminal).get("https://feeds.example.com/show.rss")

        assertEquals(listOf("https://feeds.example.com/show.rss"), terminal.attempted)
    }

    @Test
    fun `falls back to cleartext when the secure attempt fails`() {
        val terminal = RecordingInterceptor(failFirstWith = SSLHandshakeException("no tls here"))

        val response = clientWith(terminal).get("http://legacy.example.com/ep1.mp3")

        assertEquals(
            listOf("https://legacy.example.com/ep1.mp3", "http://legacy.example.com/ep1.mp3"),
            terminal.attempted,
        )
        assertEquals(200, response.code)
    }

    @Test
    fun `reports both failures when the fallback fails too`() {
        val secureFailure = SSLHandshakeException("no tls here")
        val terminal = RecordingInterceptor(
            failFirstWith = secureFailure,
            // What Android throws when the network security config forbids cleartext.
            failSecondWith = UnknownServiceException("CLEARTEXT not permitted"),
        )

        try {
            clientWith(terminal).get("http://legacy.example.com/ep1.mp3")
            fail("expected the cleartext failure to propagate")
        } catch (e: IOException) {
            assertTrue("expected the cleartext failure, got $e", e is UnknownServiceException)
            assertEquals(listOf<Throwable>(secureFailure), e.suppressed.toList())
        }
    }

    @Test
    fun `leaves an explicit non-default port alone`() {
        // https on 8080 would point TLS at a port nobody promised would speak it.
        val terminal = RecordingInterceptor()

        clientWith(terminal).get("http://localhost:8080/ep1.mp3")

        assertEquals(listOf("http://localhost:8080/ep1.mp3"), terminal.attempted)
    }

    @Test
    fun `leaves methods with side effects alone`() {
        val terminal = RecordingInterceptor()

        clientWith(terminal).newCall(
            Request.Builder()
                .url("http://api.example.com/track")
                .post(ByteArray(0).toRequestBody(null))
                .build(),
        ).execute().close()

        assertEquals(listOf("http://api.example.com/track"), terminal.attempted)
    }

    /** Builds a client whose only two interceptors are the one under test and [terminal]. */
    private fun clientWith(terminal: RecordingInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpsUpgradeInterceptor())
        .addInterceptor(terminal)
        .build()

    /** Runs a GET and closes the body, returning the response for status assertions. */
    private fun OkHttpClient.get(url: String): Response =
        newCall(Request.Builder().url(url).build()).execute().also { it.close() }

    /**
     * The end of the chain: records the URL it was handed and answers from memory, so no connection
     * is ever attempted and the scheme under test is never actually dialled.
     *
     * @param failFirstWith thrown instead of answering the first request.
     * @param failSecondWith thrown instead of answering the second request.
     */
    private class RecordingInterceptor(
        private val failFirstWith: IOException? = null,
        private val failSecondWith: IOException? = null,
    ) : Interceptor {

        /** Every URL that reached the end of the chain, in order. */
        val attempted = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            attempted += request.url.toString()
            when (attempted.size) {
                1 -> failFirstWith?.let { throw it }
                2 -> failSecondWith?.let { throw it }
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("audio".toByteArray().toResponseBody(null))
                .build()
        }
    }
}

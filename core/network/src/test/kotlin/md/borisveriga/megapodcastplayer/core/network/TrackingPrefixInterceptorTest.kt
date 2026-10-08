package md.borisveriga.megapodcastplayer.core.network

import java.io.IOException
import java.net.UnknownHostException
import md.borisveriga.megapodcastplayer.core.network.TrackingPrefixInterceptor.Companion.untrackedUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [TrackingPrefixInterceptor]: first the policy — which URLs are unwrapped into what —
 * then the interceptor in a real chain, where what matters is which URLs were *attempted*, in order.
 * A request to a redirector that was meant to be skipped is the bug, however the call ends.
 */
class TrackingPrefixInterceptorTest {

    // --- The policy -----------------------------------------------------------------------------

    @Test
    fun `a podtrac prefix is removed and the redirector's scheme kept`() {
        assertEquals(
            "http://traffic.libsyn.com/show/ep1.mp3",
            untracked("http://dts.podtrac.com/redirect.mp3/traffic.libsyn.com/show/ep1.mp3"),
        )
        assertEquals(
            "https://cdn.example.com/ep1.m4a",
            untracked("https://dts.podtrac.com/redirect.m4a/cdn.example.com/ep1.m4a"),
        )
        assertEquals(
            "https://cdn.example.com/ep1.mp3",
            untracked("https://www.podtrac.com/pts/redirect.mp3/cdn.example.com/ep1.mp3"),
        )
    }

    /** The shape seen in the wild: three services, each in front of the next. */
    @Test
    fun `stacked prefixes are all removed`() {
        assertEquals(
            "https://traffic.megaphone.fm/ABC123.mp3?updated=1700000000",
            untracked(
                "https://dts.podtrac.com/redirect.mp3/chtbl.com/track/7G8E2/pdst.fm/e/" +
                    "traffic.megaphone.fm/ABC123.mp3?updated=1700000000",
            ),
        )
    }

    @Test
    fun `every listed service is recognised`() {
        val real = "cdn.example.com/show/ep1.mp3"
        listOf(
            "https://chtbl.com/track/7G8E2/$real",
            "https://pdst.fm/e/$real",
            "https://prfx.byspotify.com/e/$real",
            "https://op3.dev/e/$real",
            "https://op3.dev/e,pg=9b024349-ccf0-5f69-a609-6b82873eab3c/$real",
            "https://pscrb.fm/rss/p/$real",
            "https://verifi.podscribe.com/rss/p/$real",
            "https://mgln.ai/e/123/$real",
            "https://clrtpod.com/m/$real",
            "https://arttrk.com/p/ABCDE/$real",
            "https://tracking.swap.fm/track/xyz/$real",
        ).forEach { prefixed ->
            assertEquals(prefixed, "https://$real", untracked(prefixed))
        }
    }

    /** OP3 allows the real URL to be written with its scheme, which then wins. */
    @Test
    fun `an embedded scheme is honoured`() {
        assertEquals(
            "https://cdn.example.com/ep1.mp3",
            untracked("http://op3.dev/e/https://cdn.example.com/ep1.mp3"),
        )
    }

    /** Re-parsing a decoded path would turn `%20` into a space and the URL into a reject. */
    @Test
    fun `escapes in the real path survive`() {
        assertEquals(
            "https://cdn.example.com/My%20Show/ep%231.mp3",
            untracked("https://pdst.fm/e/cdn.example.com/My%20Show/ep%231.mp3"),
        )
    }

    @Test
    fun `a port on the real host is kept`() {
        assertEquals(
            "https://cdn.example.com:8443/ep1.mp3",
            untracked("https://pdst.fm/e/cdn.example.com:8443/ep1.mp3"),
        )
    }

    @Test
    fun `a URL with no prefix is left alone`() {
        assertNull(untracked("https://traffic.libsyn.com/show/ep1.mp3"))
        assertNull(untracked("https://feeds.example.com/podtrac.rss"))
    }

    /** Conservative by construction: a path that does not continue with a host is not a prefix. */
    @Test
    fun `a redirector path that does not lead to a host is left alone`() {
        assertNull(untracked("https://dts.podtrac.com/redirect.mp3/"))
        assertNull(untracked("https://dts.podtrac.com/pixel.gif"))
        assertNull(untracked("https://pdst.fm/e/not-a-host/ep1.mp3"))
        assertNull(untracked("https://chtbl.com/track/7G8E2"))
        assertNull(untracked("https://op3.dev/stats/show"))
    }

    /** The bound exists so a URL built to nest forever cannot keep the interceptor busy. */
    @Test
    fun `unwrapping stops after a bounded number of prefixes`() {
        val nested = "pdst.fm/e/".repeat(20) + "cdn.example.com/ep1.mp3"

        val result = untracked("https://$nested")

        // Still a pdst.fm URL, with eight of its twenty layers removed.
        assertEquals("https://" + "pdst.fm/e/".repeat(12) + "cdn.example.com/ep1.mp3", result)
    }

    // --- The interceptor in a chain -------------------------------------------------------------

    @Test
    fun `the direct URL is requested and the redirector never is`() {
        val terminal = RecordingInterceptor()

        val response = clientWith(terminal)
            .get("https://dts.podtrac.com/redirect.mp3/cdn.example.com/ep1.mp3")

        assertEquals(listOf("https://cdn.example.com/ep1.mp3"), terminal.attempted)
        assertEquals(200, response.code)
    }

    @Test
    fun `an error status sends the request back to the published URL`() {
        val terminal = RecordingInterceptor(statuses = listOf(404, 200))
        val published = "https://pdst.fm/e/cdn.example.com/ep1.mp3"

        val response = clientWith(terminal).get(published)

        assertEquals(listOf("https://cdn.example.com/ep1.mp3", published), terminal.attempted)
        assertEquals(200, response.code)
    }

    @Test
    fun `a transport failure sends the request back to the published URL`() {
        val terminal = RecordingInterceptor(failFirstWith = UnknownHostException("cdn.example.com"))
        val published = "https://pdst.fm/e/cdn.example.com/ep1.mp3"

        val response = clientWith(terminal).get(published)

        assertEquals(listOf("https://cdn.example.com/ep1.mp3", published), terminal.attempted)
        assertEquals(200, response.code)
    }

    @Test
    fun `when both fail, the published URL's failure is thrown with the direct one attached`() {
        val directFailure = UnknownHostException("cdn.example.com")
        val terminal = RecordingInterceptor(
            failFirstWith = directFailure,
            failSecondWith = IOException("redirector down"),
        )

        try {
            clientWith(terminal).get("https://pdst.fm/e/cdn.example.com/ep1.mp3")
            fail("expected the published URL's failure to propagate")
        } catch (e: IOException) {
            assertEquals("redirector down", e.message)
            assertEquals(listOf<Throwable>(directFailure), e.suppressed.toList())
        }
    }

    /** A Range header is how a seek and a resumed download ask; dropping it restarts from zero. */
    @Test
    fun `headers travel with the direct request`() {
        val terminal = RecordingInterceptor()

        clientWith(terminal).newCall(
            Request.Builder()
                .url("https://pdst.fm/e/cdn.example.com/ep1.mp3")
                .header("Range", "bytes=1000-")
                .build(),
        ).execute().close()

        assertEquals("bytes=1000-", terminal.lastRange)
    }

    /** Placed first, so a cleartext direct URL is upgraded exactly as a published one would be. */
    @Test
    fun `a cleartext direct URL is still upgraded to https`() {
        val terminal = RecordingInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(TrackingPrefixInterceptor())
            .addInterceptor(HttpsUpgradeInterceptor())
            .addInterceptor(terminal)
            .build()

        client.get("http://dts.podtrac.com/redirect.mp3/traffic.libsyn.com/ep1.mp3")

        assertEquals(listOf("https://traffic.libsyn.com/ep1.mp3"), terminal.attempted)
    }

    @Test
    fun `methods with side effects are left alone`() {
        val terminal = RecordingInterceptor()

        clientWith(terminal).newCall(
            Request.Builder()
                .url("https://pdst.fm/e/cdn.example.com/ep1.mp3")
                .post(ByteArray(0).toRequestBody(null))
                .build(),
        ).execute().close()

        assertEquals(listOf("https://pdst.fm/e/cdn.example.com/ep1.mp3"), terminal.attempted)
    }

    @Test
    fun `an unprefixed request passes through once`() {
        val terminal = RecordingInterceptor(statuses = listOf(404))

        val response = clientWith(terminal).get("https://cdn.example.com/missing.mp3")

        // A genuine 404 on a published URL costs nothing extra.
        assertEquals(listOf("https://cdn.example.com/missing.mp3"), terminal.attempted)
        assertEquals(404, response.code)
    }

    /** [untrackedUrlOrNull] over strings, for readable assertions. */
    private fun untracked(url: String): String? = untrackedUrlOrNull(url.toHttpUrl())?.toString()

    /** Builds a client whose only two interceptors are the one under test and [terminal]. */
    private fun clientWith(terminal: RecordingInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(TrackingPrefixInterceptor())
        .addInterceptor(terminal)
        .build()

    /** Runs a GET and closes the body, returning the response for status assertions. */
    private fun OkHttpClient.get(url: String): Response =
        newCall(Request.Builder().url(url).build()).execute().also { it.close() }

    /**
     * The end of the chain: records each URL it is handed and answers from memory, so nothing is
     * ever dialled.
     *
     * @param statuses the status to answer each attempt with, in order; 200 once they run out.
     * @param failFirstWith thrown instead of answering the first request.
     * @param failSecondWith thrown instead of answering the second request.
     */
    private class RecordingInterceptor(
        private val statuses: List<Int> = emptyList(),
        private val failFirstWith: IOException? = null,
        private val failSecondWith: IOException? = null,
    ) : Interceptor {

        /** Every URL that reached the end of the chain, in order. */
        val attempted = mutableListOf<String>()

        /** The Range header of the last request, if it had one. */
        var lastRange: String? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            attempted += request.url.toString()
            lastRange = request.header("Range")
            when (attempted.size) {
                1 -> failFirstWith?.let { throw it }
                2 -> failSecondWith?.let { throw it }
            }
            val code = statuses.getOrElse(attempted.size - 1) { 200 }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("status $code")
                .body("audio".toByteArray().toResponseBody(null))
                .build()
        }
    }
}

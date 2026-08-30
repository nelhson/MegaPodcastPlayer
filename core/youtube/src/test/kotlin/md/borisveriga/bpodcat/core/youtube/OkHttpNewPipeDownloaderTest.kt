package md.borisveriga.bpodcat.core.youtube

import java.io.IOException
import java.net.HttpURLConnection
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * Tests the bridge between NewPipe's downloader contract and OkHttp.
 *
 * Worth testing precisely because it is the seam between two libraries: a mistake here (a dropped
 * header, a body sent on a GET, a 429 reported as an ordinary failure) would surface much later as
 * an unexplained extraction failure with nothing pointing back at this file.
 */
class OkHttpNewPipeDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: OkHttpNewPipeDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = OkHttpNewPipeDownloader(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun url(path: String = "/") = server.url(path).toString()

    @Test
    fun `performs a get and returns code message headers and body`() {
        server.enqueue(
            MockResponse.Builder()
                .code(HttpURLConnection.HTTP_OK)
                .addHeader("Content-Type", "application/json")
                .body("""{"ok":true}""")
                .build(),
        )

        val response = downloader.execute(Request.newBuilder().get(url()).build())

        assertEquals(200, response.responseCode())
        assertEquals("""{"ok":true}""", response.responseBody())
        assertEquals("application/json", response.getHeader("Content-Type"))
    }

    @Test
    fun `passes request headers through`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        downloader.execute(
            Request.newBuilder()
                .get(url())
                .setHeader("X-Youtube-Client-Name", "1")
                .build(),
        )

        assertEquals("1", server.takeRequest().headers["X-Youtube-Client-Name"])
    }

    @Test
    fun `sends a desktop user agent when the extractor supplies none`() {
        // googlevideo occasionally validates that extraction and playback agree on the agent, so a
        // default here is not cosmetic.
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        downloader.execute(Request.newBuilder().get(url()).build())

        assertEquals(YOUTUBE_USER_AGENT, server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun `does not override a user agent the extractor chose`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        downloader.execute(
            Request.newBuilder().get(url()).setHeader("User-Agent", "com.google.android.youtube/1")
                .build(),
        )

        assertEquals("com.google.android.youtube/1", server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun `posts the body the extractor supplied`() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        downloader.execute(
            Request.newBuilder().post(url(), """{"videoId":"x"}""".toByteArray()).build(),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("""{"videoId":"x"}""", recorded.body?.utf8())
    }

    @Test
    fun `performs a head request without a body`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val response = downloader.execute(Request.newBuilder().head(url()).build())

        assertEquals(200, response.responseCode())
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun `reports a 429 as a captcha challenge`() {
        // The extractor's callers watch specifically for this to report rate limiting rather than
        // a generic failure, so mapping it here is what makes that message reachable.
        server.enqueue(MockResponse.Builder().code(429).body("go away").build())

        try {
            downloader.execute(Request.newBuilder().get(url()).build())
            fail("Expected ReCaptchaException for HTTP 429")
        } catch (e: ReCaptchaException) {
            assertTrue(e.url, e.url.startsWith(server.url("/").toString()))
        }
    }

    @Test
    fun `reports the url the bytes actually came from after a redirect`() {
        // The extractor resolves relative URLs found in the body against this.
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", server.url("/final").toString())
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val response = downloader.execute(Request.newBuilder().get(url("/start")).build())

        assertTrue(response.latestUrl(), response.latestUrl().endsWith("/final"))
    }

    @Test
    fun `refuses a body larger than the cap instead of buffering it`() {
        // The extractor runs in the player's process and this bridge buffers the whole body into a
        // String, so an oversized response from a host nobody here controls is an OOM that takes
        // playback down with it. Refusing it is the cheap half of that trade.
        server.enqueue(
            MockResponse.Builder()
                .code(HttpURLConnection.HTTP_OK)
                .body("x".repeat(3 * 1024 * 1024))
                .build(),
        )

        try {
            downloader.execute(Request.newBuilder().get(url()).build())
            fail("Expected an IOException for an oversized body")
        } catch (expected: IOException) {
            assertTrue(expected.message, expected.message.orEmpty().contains("exceeded"))
        }
    }

    @Test
    fun `accepts a body comfortably under the cap`() {
        // Guards the other side of the boundary: real InnerTube payloads run to a few hundred
        // kilobytes, and the cap must not start rejecting them.
        val body = "y".repeat(512 * 1024)
        server.enqueue(MockResponse.Builder().code(HttpURLConnection.HTTP_OK).body(body).build())

        val response = downloader.execute(Request.newBuilder().get(url()).build())

        assertEquals(body.length, response.responseBody().length)
    }

    @Test
    fun `does not fail on an error status`() {
        // A 404 is information the extractor acts on, not an exception for this layer to raise.
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())

        val response = downloader.execute(Request.newBuilder().get(url()).build())

        assertEquals(404, response.responseCode())
        assertEquals("nope", response.responseBody())
    }
}

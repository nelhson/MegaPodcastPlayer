package md.borisveriga.bpodcat.core.media.di

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests that audio never reaches OkHttp's response cache.
 *
 * This is a regression test for a measured bug rather than a hypothetical one. Episode bodies are
 * routinely larger than the shared client's 20 MB cache, and OkHttp caches them because Media3's
 * first request carries no `Range` header and the CDN therefore answers `200`. Downloading one
 * 28 MB episode took the cache from 19.7 MB to 16 KB — `DiskLruCache` evicted every cached feed
 * trying to fit a body that never had room, then dropped the body too.
 *
 * The test is deliberately behavioural: it serves a cacheable response and asks what ended up on
 * disk. Asserting only that `cache` is null would pass just as well against a client that had lost
 * the connection pool or the HTTPS upgrade along with it, which is why those are asserted too.
 */
class MediaOkHttpClientTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: Cache
    private lateinit var shared: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cache = Cache(temporaryFolder.newFolder("http"), CACHE_BYTES)
        shared = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(MarkerInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.close()
        cache.close()
    }

    @Test
    fun `the shared client caches a cacheable response`() {
        // The control. Without this the cache-miss assertion below would also pass against a
        // response the cache was never willing to store in the first place.
        enqueueCacheableBody()

        get(shared, "/feed.xml")

        cache.flush()
        assertTrue("the shared client should still cache feeds", cache.size() > 0L)
    }

    @Test
    fun `the audio client stores nothing`() {
        enqueueCacheableBody()

        get(mediaOkHttpClient(shared), "/episode.mp3")

        cache.flush()
        assertEquals("audio must not be written to the response cache", 0L, cache.size())
    }

    @Test
    fun `the audio client has no cache at all`() {
        assertNull(mediaOkHttpClient(shared).cache)
    }

    @Test
    fun `the audio client keeps the shared connection pool and dispatcher`() {
        // Sharing the pool is the whole reason the client is a singleton; a fresh
        // OkHttpClient() would drop it and open its own connections for every episode.
        val media = mediaOkHttpClient(shared)

        assertSame(shared.connectionPool, media.connectionPool)
        assertSame(shared.dispatcher, media.dispatcher)
    }

    @Test
    fun `the audio client keeps the interceptors`() {
        // Chiefly HttpsUpgradeInterceptor: losing it would make every cleartext enclosure URL fail
        // to open, and the failure would look nothing like a caching change.
        val media = mediaOkHttpClient(shared)

        assertEquals(shared.interceptors, media.interceptors)

        enqueueCacheableBody()
        assertEquals("the interceptor should still run", "yes", get(media, "/x").header(MARKER))
    }

    /** Serves a body the cache is unambiguously allowed to store. */
    private fun enqueueCacheableBody() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Cache-Control", "max-age=3600")
                .body("audio-bytes")
                .build(),
        )
    }

    /** Performs a GET and consumes the body, which is when OkHttp writes to the cache. */
    private fun get(client: OkHttpClient, path: String): Response =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { response ->
            response.body.string()
            response
        }

    /** Proves the derived client still runs the interceptors the shared one was built with. */
    private class MarkerInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(chain.request()).newBuilder().addHeader(MARKER, "yes").build()
    }

    private companion object {
        /** Larger than the served body, so a miss is a decision and not a lack of room. */
        const val CACHE_BYTES = 1024L * 1024L

        const val MARKER = "X-Interceptor-Ran"
    }
}

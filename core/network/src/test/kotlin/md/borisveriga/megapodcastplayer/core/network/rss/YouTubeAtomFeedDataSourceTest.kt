package md.borisveriga.megapodcastplayer.core.network.rss

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Tests for [YouTubeAtomFeedDataSource] against a local server.
 *
 * A real Retrofit client over a [MockWebServer] rather than a mocked [FeedApi], because what is
 * under test is the plumbing — which URL is asked for, how a status and a body are turned into a
 * channel or a failure — and a mock of the API would only assert that the mock was called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeAtomFeedDataSourceTest {

    private val server = MockWebServer()
    private lateinit var dataSource: YouTubeAtomFeedDataSource

    private val playlistId = "PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0"

    @Before
    fun setUp() {
        server.start()
        // The data source builds an absolute youtube.com URL; the interceptor points it at the
        // local server so the request shape is still the production one.
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val redirected = original.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(original.newBuilder().url(redirected).build())
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://www.youtube.com/")
            .client(client)
            .build()
            .create(FeedApi::class.java)
        dataSource = YouTubeAtomFeedDataSource(api, YouTubeAtomParser(), UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("feeds/$name")) {
            "Missing test fixture feeds/$name"
        }.use { it.readBytes().decodeToString() }

    @Test
    fun `asks for the playlist's published feed`() = runTest {
        server.enqueue(MockResponse(body = fixture("youtube_playlist.xml")))

        dataSource.fetch(playlistId)

        val url = server.takeRequest().url
        assertEquals("/feeds/videos.xml", url.encodedPath)
        assertEquals("playlist_id=$playlistId", url.encodedQuery)
    }

    @Test
    fun `sends no validators, because the endpoint honours none`() = runTest {
        server.enqueue(MockResponse(body = fixture("youtube_playlist.xml")))

        dataSource.fetch(playlistId)

        val request = server.takeRequest()
        assertEquals(null, request.headers["If-None-Match"])
        assertEquals(null, request.headers["If-Modified-Since"])
    }

    @Test
    fun `parses the feed into the playlist's channel`() = runTest {
        server.enqueue(MockResponse(body = fixture("youtube_playlist.xml")))

        val channel = dataSource.fetch(playlistId)

        assertEquals("Generic", channel.title)
        // Three entries in the fixture, one without a video id.
        assertEquals(2, channel.items.size)
        assertEquals("youtube://video/niTJ2221aS8", channel.items.first().audioUrl)
    }

    @Test
    fun `a non-2xx status is a network failure`() = runTest {
        server.enqueue(MockResponse(code = 404))

        try {
            dataSource.fetch(playlistId)
            fail("Expected IOException for a 404")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("404"))
        }
    }

    @Test
    fun `an html page served with a 200 is a parse failure`() = runTest {
        // What a private or deleted playlist looks like from here.
        server.enqueue(MockResponse(body = "<html><body>This playlist is private</body></html>"))

        try {
            dataSource.fetch(playlistId)
            fail("Expected RssParseException for an HTML body")
        } catch (_: RssParseException) {
            // Expected.
        }
    }
}

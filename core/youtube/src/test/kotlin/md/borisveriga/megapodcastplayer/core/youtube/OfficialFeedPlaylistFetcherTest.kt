package md.borisveriga.megapodcastplayer.core.youtube

import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import md.borisveriga.megapodcastplayer.core.network.rss.FeedChannel
import md.borisveriga.megapodcastplayer.core.network.rss.FeedItem
import md.borisveriga.megapodcastplayer.core.network.rss.RssParseException
import md.borisveriga.megapodcastplayer.core.network.rss.YouTubeAtomFeedDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests for [OfficialFeedPlaylistFetcher].
 *
 * The feed itself is `:core:network`'s and tested there; what this adds is the cut to
 * [YouTubeSource.OFFICIAL_EPISODE_LIMIT] and the translation of a parse failure into the same
 * exception the extractor's fetcher throws for the same playlist.
 */
class OfficialFeedPlaylistFetcherTest {

    private lateinit var atomFeed: YouTubeAtomFeedDataSource
    private lateinit var fetcher: OfficialFeedPlaylistFetcher

    private val playlistId = "PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0"

    /** A video id of the right shape, unique per [index]. */
    private fun videoId(index: Int) = "video%07d".format(index).replace(' ', '0')

    private fun item(index: Int) = FeedItem(
        guid = "yt:video:${videoId(index)}",
        title = "Video $index",
        description = "",
        audioUrl = youTubeAudioSentinel(videoId(index)),
        audioLengthBytes = null,
        artworkUrl = null,
        durationMs = null,
        publishedAt = Instant.parse("2026-08-26T18:24:30Z"),
    )

    private fun channel(count: Int) = FeedChannel(
        title = "Generic",
        author = "Boris Veriga",
        description = "",
        artworkUrl = null,
        items = (1..count).map(::item),
    )

    @Before
    fun setUp() {
        atomFeed = mockk()
        fetcher = OfficialFeedPlaylistFetcher(atomFeed)
    }

    @Test
    fun `cuts the feed's fifteen to the official limit, newest first`() = runTest {
        coEvery { atomFeed.fetch(playlistId) } returns channel(count = 15)

        val channel = fetcher.fetch(playlistId)

        assertEquals(YouTubeSource.OFFICIAL_EPISODE_LIMIT, channel.items.size)
        // The first ten of the feed, in the feed's order: the cut takes from the end, not the top.
        assertEquals((1..10).map { "Video $it" }, channel.items.map { it.title })
    }

    @Test
    fun `a playlist shorter than the limit is returned whole`() = runTest {
        coEvery { atomFeed.fetch(playlistId) } returns channel(count = 3)

        assertEquals(3, fetcher.fetch(playlistId).items.size)
    }

    @Test
    fun `everything but the items is passed through`() = runTest {
        coEvery { atomFeed.fetch(playlistId) } returns channel(count = 1).copy(
            artworkUrl = "https://i.ytimg.com/vi/niTJ2221aS8/mqdefault.jpg",
        )

        val channel = fetcher.fetch(playlistId)

        assertEquals("Generic", channel.title)
        assertEquals("Boris Veriga", channel.author)
        assertEquals("https://i.ytimg.com/vi/niTJ2221aS8/mqdefault.jpg", channel.artworkUrl)
    }

    @Test
    fun `a parse failure is the playlist being unavailable`() = runTest {
        // What a private or deleted playlist looks like from the feed: an HTML page with a 200.
        val cause = RssParseException("Document contains no <feed> element")
        coEvery { atomFeed.fetch(playlistId) } throws cause

        try {
            fetcher.fetch(playlistId)
            fail("Expected YouTubePlaylistUnavailableException")
        } catch (e: YouTubePlaylistUnavailableException) {
            assertEquals(playlistId, e.playlistId)
            assertSame(cause, e.cause)
            assertTrue(e.reason, e.reason.contains("private"))
        }
    }

    @Test
    fun `a network failure passes through untouched`() = runTest {
        // The repository already knows what to do with an IOException from a feed fetch, and
        // wrapping it would turn "no network" into "playlist gone".
        val cause = IOException("Unable to resolve host")
        coEvery { atomFeed.fetch(playlistId) } throws cause

        try {
            fetcher.fetch(playlistId)
            fail("Expected IOException")
        } catch (e: IOException) {
            assertSame(cause, e)
        }
    }
}

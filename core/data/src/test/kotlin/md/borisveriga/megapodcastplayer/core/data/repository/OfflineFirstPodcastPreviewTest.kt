package md.borisveriga.megapodcastplayer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.model.PodcastSearchResult
import md.borisveriga.megapodcastplayer.core.network.itunes.ItunesRemoteDataSource
import md.borisveriga.megapodcastplayer.core.network.rss.FeedChannel
import md.borisveriga.megapodcastplayer.core.network.rss.FeedFetchResult
import md.borisveriga.megapodcastplayer.core.network.rss.FeedItem
import md.borisveriga.megapodcastplayer.core.network.rss.FeedRemoteDataSource
import md.borisveriga.megapodcastplayer.core.youtube.YouTubePlaylistFetcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for looking at a show without adding it.
 *
 * Its own class rather than more rows in `OfflineFirstPodcastRepositoryTest`, which is already as
 * long as detekt will allow — and this is a distinct question anyway. Everything else that class
 * asserts is about what a fetch *writes*; every test here is about a fetch that must write nothing.
 *
 * The database is real, because "nothing was stored" is only worth asserting against a store that
 * could have been written to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineFirstPodcastPreviewTest {

    private val feedUrl =
        "https://feeds.soundcloud.com/users/soundcloud:users:291337106/sounds.rss"

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var feeds: FeedRemoteDataSource
    private lateinit var repository: OfflineFirstPodcastRepository

    private val appleResult = PodcastSearchResult(
        itunesId = 1209828744L,
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        feedUrl = feedUrl,
        artworkUrl = "https://example.com/apple-art-600.jpg",
        episodeCount = 500,
        genres = listOf("Technology"),
    )

    private fun feedItem(guid: String) = FeedItem(
        guid = guid,
        title = "Episode $guid",
        description = "notes",
        audioUrl = "https://cdn.example.com/$guid.mp3",
        audioLengthBytes = 1_000L,
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
    )

    private fun channel(vararg items: FeedItem) = FeedChannel(
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        description = "Шоу о разработке",
        artworkUrl = "https://example.com/feed-art.jpg",
        items = items.toList(),
    )

    /** Stubs the one fetch a preview makes: no validators, because it has never fetched before. */
    private fun stubFetch(vararg items: FeedItem) {
        coEvery { feeds.fetch(feedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(*items), etag = null, lastModified = null)
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()
        feeds = mockk()
        repository = OfflineFirstPodcastRepository(
            podcastDao = database.podcastDao(),
            episodeDao = database.episodeDao(),
            itunes = mockk<ItunesRemoteDataSource>(),
            feeds = feeds,
            youTubePlaylists = mockk<YouTubePlaylistFetcher>(),
            autoDownloadScheduler = mockk(relaxed = true),
            clock = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC),
            crashReporter = mockk<CrashReporter>(relaxed = true),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Looking at a show is not subscribing to it, and the whole value of the preview sheet rests on
     * that being literally true: nothing may be written, because the way back from a subscription
     * is an unsubscribe, and an unsubscribe takes every position and download with it.
     */
    @Test
    fun `previewing a show stores nothing at all`() = runTest {
        stubFetch(feedItem("a"), feedItem("b"), feedItem("c"))

        val loaded = repository.preview(appleResult, episodeLimit = 2)
            as PodcastPreviewResult.Loaded

        assertEquals("Podlodka Podcast", loaded.preview.podcast.title)
        // Apple's 600px artwork wins over the feed's, exactly as it does on an add.
        assertEquals("https://example.com/apple-art-600.jpg", loaded.preview.podcast.artworkUrl)
        // The limit bounds the list; the total is what says how big the show really is.
        assertEquals(listOf("Episode a", "Episode b"), loaded.preview.episodes.map { it.title })
        assertEquals(3, loaded.preview.totalEpisodeCount)

        assertTrue(repository.observeLibrary().first().isEmpty())
        assertTrue(repository.observeEpisodes(loaded.preview.podcast.id).first().isEmpty())
    }

    /**
     * The ids a preview carries are the ids a subscription would write, which is what lets an
     * episode played from the sheet and the same episode played afterwards be one episode as far as
     * the player and the download cache are concerned.
     */
    @Test
    fun `a previewed show keeps its ids when it is added`() = runTest {
        stubFetch(feedItem("a"))

        val loaded = repository.preview(appleResult) as PodcastPreviewResult.Loaded
        val added = repository.addFromSearchResult(appleResult) as AddPodcastResult.Added

        assertEquals(added.podcast.id, loaded.preview.podcast.id)
        assertEquals(
            repository.observeEpisodes(added.podcast.id).first().map { it.id },
            loaded.preview.episodes.map { it.id },
        )
    }

    @Test
    fun `a show apple lists but publishes no feed for cannot be previewed`() = runTest {
        val exclusive = appleResult.copy(feedUrl = null)

        assertTrue(repository.preview(exclusive) is PodcastPreviewResult.NoFeedAvailable)
    }

    @Test
    fun `a feed that cannot be read comes back as a failure rather than an empty preview`() =
        runTest {
            coEvery { feeds.fetch(feedUrl, null, null) } throws IllegalStateException("boom")

            assertTrue(repository.preview(appleResult) is PodcastPreviewResult.Failed)
        }
}

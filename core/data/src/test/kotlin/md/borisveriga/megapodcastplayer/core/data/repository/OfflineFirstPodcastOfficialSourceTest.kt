package md.borisveriga.megapodcastplayer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
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
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.PodcastSearchResult
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import md.borisveriga.megapodcastplayer.core.network.itunes.ItunesRemoteDataSource
import md.borisveriga.megapodcastplayer.core.network.rss.FeedChannel
import md.borisveriga.megapodcastplayer.core.network.rss.FeedFetchResult
import md.borisveriga.megapodcastplayer.core.network.rss.FeedItem
import md.borisveriga.megapodcastplayer.core.network.rss.FeedRemoteDataSource
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
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
 * [OfflineFirstPodcastRepository] under the official YouTube source.
 *
 * Its own class beside [OfflineFirstPodcastRepositoryTest], which was already as long as a test
 * class is allowed to be. Same real database, same stubbed remotes; what this adds is the second
 * fetcher and the preference that chooses between them, and the one promise the source makes —
 * that switching hides and never deletes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineFirstPodcastOfficialSourceTest {

    private val podlodkaFeedUrl =
        "https://feeds.soundcloud.com/users/soundcloud:users:291337106/sounds.rss"

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var itunes: ItunesRemoteDataSource
    private lateinit var feeds: FeedRemoteDataSource
    private lateinit var youTubePlaylists: YouTubePlaylistFetcher
    private lateinit var officialPlaylists: YouTubePlaylistFetcher
    private lateinit var preferences: UserPreferencesDataSource
    private lateinit var repository: OfflineFirstPodcastRepository

    private val clock = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC)

    private fun feedItem(guid: String, title: String = "Episode $guid") = FeedItem(
        guid = guid,
        title = title,
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

    private val appleResult = PodcastSearchResult(
        itunesId = 1209828744L,
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        feedUrl = podlodkaFeedUrl,
        artworkUrl = "https://example.com/apple-art-600.jpg",
        episodeCount = 500,
        genres = listOf("Technology"),
    )

    private val playlistId = "PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0"

    /** One video as either fetcher emits it: sentinel audio, thumbnail derived from the id. */
    private fun youTubeItem(videoId: String) = FeedItem(
        guid = "yt:video:$videoId",
        title = "Video $videoId",
        description = "notes",
        audioUrl = youTubeAudioSentinel(videoId),
        audioLengthBytes = null,
        artworkUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
        durationMs = null,
        publishedAt = Instant.parse("2026-08-26T18:24:30Z"),
    )

    private fun youTubeChannel(vararg items: FeedItem) = FeedChannel(
        title = "Generic",
        author = "Boris Veriga",
        description = "",
        artworkUrl = "https://i.ytimg.com/vi/niTJ2221aS8/mqdefault.jpg",
        items = items.toList(),
    )

    /** Stubs the *extractor's* read. */
    private fun stubPlaylistFetch(vararg items: FeedItem) {
        coEvery { youTubePlaylists.fetch(playlistId) } returns youTubeChannel(*items)
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()
        itunes = mockk()
        feeds = mockk()
        youTubePlaylists = mockk()
        officialPlaylists = mockk()
        preferences = UserPreferencesDataSource(InMemoryPreferencesDataStore())
        repository = OfflineFirstPodcastRepository(
            podcastDao = database.podcastDao(),
            episodeDao = database.episodeDao(),
            itunes = itunes,
            feeds = feeds,
            extractorPlaylists = youTubePlaylists,
            officialPlaylists = officialPlaylists,
            userPreferences = preferences,
            autoDownloadScheduler = mockk(relaxed = true),
            clock = clock,
            crashReporter = mockk<CrashReporter>(relaxed = true),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Stubs the *official* fetcher, which the repository asks under [YouTubeSource.OFFICIAL]. */
    private fun stubOfficialFetch(vararg items: FeedItem) {
        coEvery { officialPlaylists.fetch(playlistId) } returns youTubeChannel(*items)
    }

    /** Adds the playlist under the extractor with [count] videos, newest first. */
    private suspend fun addPlaylistWithExtractor(count: Int): AddPodcastResult.Added {
        stubPlaylistFetch(*(1..count).map { youTubeItem("video%06d".format(it)) }.toTypedArray())
        return repository.addFromInput(
            "https://www.youtube.com/playlist?list=$playlistId",
        ) as AddPodcastResult.Added
    }

    @Test
    fun `under the official source a playlist is read from the feed, not the extractor`() = runTest {
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        stubOfficialFetch(youTubeItem("niTJ2221aS8"))

        val result = repository.addFromInput("https://www.youtube.com/playlist?list=$playlistId")

        assertTrue("Expected Added, got $result", result is AddPodcastResult.Added)
        coVerify { officialPlaylists.fetch(playlistId) }
        coVerify(exactly = 0) { youTubePlaylists.fetch(any()) }
    }

    @Test
    fun `the source is read on every fetch, so a refresh follows a change of mind`() = runTest {
        val added = addPlaylistWithExtractor(count = 1)
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        stubOfficialFetch(youTubeItem("video000001"), youTubeItem("video000002"))

        val discovered = repository.refresh(added.podcast.id).getOrThrow()

        assertEquals(1, discovered)
        coVerify(exactly = 1) { officialPlaylists.fetch(playlistId) }
    }

    @Test
    fun `under the official source a show shows its newest ten and no more`() = runTest {
        val added = addPlaylistWithExtractor(count = 34)

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        val shown = repository.observeEpisodes(added.podcast.id).first()
        assertEquals(YouTubeSource.OFFICIAL_EPISODE_LIMIT, shown.size)
        // The top of the hand order, which for an uploads playlist is the newest.
        assertEquals((1..10).map { "yt:video:video%06d".format(it) }, shown.map { it.guid })
    }

    @Test
    fun `the rows past the cut are hidden, not deleted, and come back with the extractor`() = runTest {
        val added = addPlaylistWithExtractor(count = 34)
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        assertEquals(10, repository.observeEpisodes(added.podcast.id).first().size)

        preferences.setYouTubeSource(YouTubeSource.EXTRACTOR)

        assertEquals(34, repository.observeEpisodes(added.podcast.id).first().size)
    }

    @Test
    fun `under the official source a youtube download reads as not downloaded`() = runTest {
        val added = addPlaylistWithExtractor(count = 2)
        val stored = repository.observeEpisodes(added.podcast.id).first()
        database.episodeDao().updateDownloadState(stored.first().id, DownloadState.COMPLETED, 10_000L, 100f)

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        val shown = repository.observeEpisodes(added.podcast.id).first()
        assertTrue(shown.all { it.downloadState == DownloadState.NOT_DOWNLOADED })
        assertEquals(
            DownloadState.NOT_DOWNLOADED,
            repository.observeEpisode(stored.first().id).first()?.downloadState,
        )
        // The row itself still says what is on the device.
        assertEquals(DownloadState.COMPLETED, database.episodeDao().getById(stored.first().id)?.downloadState)
    }

    @Test
    fun `an rss show is unaffected by the youtube source`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a"), feedItem("b")), etag = null, lastModified = null)
        val added = repository.addFromInput("1209828744") as AddPodcastResult.Added
        database.episodeDao().updateDownloadState(
            repository.observeEpisodes(added.podcast.id).first().first().id,
            DownloadState.COMPLETED,
            10_000L,
            100f,
        )

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        val shown = repository.observeEpisodes(added.podcast.id).first()
        assertEquals(2, shown.size)
        assertTrue(shown.any { it.downloadState == DownloadState.COMPLETED })
    }

    @Test
    fun `under the official source a rebuild withdraws nothing`() = runTest {
        val added = addPlaylistWithExtractor(count = 34)
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        // The feed shows ten of the thirty-four; a rebuild that believed it would drop twenty-four.
        stubOfficialFetch(*(1..10).map { youTubeItem("video%06d".format(it)) }.toTypedArray())

        val result = repository.rebuild(added.podcast.id).getOrThrow()

        assertEquals(10, result.episodeCount)
        preferences.setYouTubeSource(YouTubeSource.EXTRACTOR)
        assertEquals(34, repository.observeEpisodes(added.podcast.id).first().size)
    }

    @Test
    fun `under the official source the in-progress shelf leaves youtube out`() = runTest {
        val added = addPlaylistWithExtractor(count = 1)
        val video = repository.observeEpisodes(added.podcast.id).first().single()
        database.episodeDao().updatePosition(video.id, 90_000L)
        assertEquals(1, repository.observeInProgressEpisodes(limit = 5).first().size)

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        // The shelf offers to resume in the app's player, which under this source refuses the video.
        assertTrue(repository.observeInProgressEpisodes(limit = 5).first().isEmpty())
    }

    @Test
    fun `the youtube source round trips through the repository`() = runTest {
        assertEquals(YouTubeSource.DEFAULT, repository.observeYouTubeSource().first())

        repository.setYouTubeSource(YouTubeSource.OFFICIAL)

        assertEquals(YouTubeSource.OFFICIAL, repository.observeYouTubeSource().first())
    }
}

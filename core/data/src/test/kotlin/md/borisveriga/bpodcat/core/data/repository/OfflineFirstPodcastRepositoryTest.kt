package md.borisveriga.bpodcat.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.database.BPodcatDatabase
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.PodcastSearchResult
import md.borisveriga.bpodcat.core.model.PodcastSource
import md.borisveriga.bpodcat.core.model.youTubeAudioSentinel
import md.borisveriga.bpodcat.core.model.youTubePlaylistFeedUrl
import md.borisveriga.bpodcat.core.network.itunes.ItunesRemoteDataSource
import md.borisveriga.bpodcat.core.network.rss.FeedChannel
import md.borisveriga.bpodcat.core.network.rss.FeedFetchResult
import md.borisveriga.bpodcat.core.network.rss.FeedItem
import md.borisveriga.bpodcat.core.network.rss.FeedRemoteDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [OfflineFirstPodcastRepository] against a real in-memory database and stubbed remotes.
 *
 * The database is real because the behaviour that matters here — "a refresh adds episodes without
 * touching user state" — lives in SQL, and a fake DAO would test nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineFirstPodcastRepositoryTest {

    private val podlodkaFeedUrl =
        "https://feeds.soundcloud.com/users/soundcloud:users:291337106/sounds.rss"

    private lateinit var database: BPodcatDatabase
    private lateinit var itunes: ItunesRemoteDataSource
    private lateinit var feeds: FeedRemoteDataSource
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

    // --- YouTube fixtures -------------------------------------------------

    private val playlistId = "PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0"
    private val playlistFeedUrl = youTubePlaylistFeedUrl(playlistId)

    /** A parsed YouTube entry, exactly as YouTubeAtomParser emits it: sentinel audio, no duration. */
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

    /** Stubs the playlist fetch for any validators, since YouTube's feed endpoint sends none. */
    private fun stubPlaylistFetch(vararg items: FeedItem) {
        coEvery {
            feeds.fetch(playlistFeedUrl, any(), any(), PodcastSource.YOUTUBE)
        } returns FeedFetchResult.Fetched(
            youTubeChannel(*items),
            etag = null,
            lastModified = null,
        )
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BPodcatDatabase::class.java,
        ).allowMainThreadQueries().build()
        itunes = mockk()
        feeds = mockk()
        repository = OfflineFirstPodcastRepository(
            podcastDao = database.podcastDao(),
            episodeDao = database.episodeDao(),
            itunes = itunes,
            feeds = feeds,
            // Relaxed: what a refresh does with the ids it discovers is MediaDownloadRepository's
            // business, and is tested there.
            autoDownloadScheduler = mockk(relaxed = true),
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `adds a show from a pasted apple link`() = runTest {
        coEvery { itunes.lookup(1209828744L) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a"), feedItem("b")), etag = "W/\"v1\"", lastModified = null)

        val result = repository.addFromInput(
            "https://podcasts.apple.com/us/podcast/podlodka-podcast/id1209828744",
        )

        assertTrue("Expected Added, got $result", result is AddPodcastResult.Added)
        val added = result as AddPodcastResult.Added
        assertEquals("Podlodka Podcast", added.podcast.title)
        assertEquals(1209828744L, added.podcast.itunesId)
        assertEquals(2, added.episodeCount)
        assertEquals(2, repository.observeEpisodes(added.podcast.id).first().size)
    }

    @Test
    fun `prefers apple artwork over the feed artwork`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = null, lastModified = null)

        val added = repository.addFromInput("1209828744") as AddPodcastResult.Added

        assertEquals("https://example.com/apple-art-600.jpg", added.podcast.artworkUrl)
    }

    @Test
    fun `episodes present at add time are not badged as new`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a"), feedItem("b")), etag = null, lastModified = null)

        val added = repository.addFromInput("1209828744") as AddPodcastResult.Added
        val episodes = repository.observeEpisodes(added.podcast.id).first()

        assertTrue(episodes.none { it.isNew })
    }

    @Test
    fun `adding the same feed twice reports it is already in the library`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, any(), any()) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = null, lastModified = null)

        repository.addFromInput("1209828744")
        // Second time via the raw RSS URL: same feed, different input shape.
        val second = repository.addFromInput(podlodkaFeedUrl)

        assertTrue("Expected AlreadyInLibrary, got $second", second is AddPodcastResult.AlreadyInLibrary)
        assertEquals(1, repository.observeLibrary().first().size)
    }

    @Test
    fun `reports apple exclusives that publish no feed`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult.copy(feedUrl = null)

        val result = repository.addFromInput("1209828744")

        assertEquals(AddPodcastResult.NoFeedAvailable("Podlodka Podcast"), result)
    }

    @Test
    fun `reports an unknown apple id`() = runTest {
        coEvery { itunes.lookup(any()) } returns null

        assertEquals(AddPodcastResult.NotFound, repository.addFromInput("1209828744"))
    }

    @Test
    fun `rejects input that is not a link`() = runTest {
        assertEquals(AddPodcastResult.InvalidInput, repository.addFromInput("подлодка"))
    }

    @Test
    fun `surfaces a feed failure instead of storing a broken show`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } throws IOException("boom")

        val result = repository.addFromInput("1209828744")

        assertTrue(result is AddPodcastResult.Failed)
        assertTrue(repository.observeLibrary().first().isEmpty())
    }

    @Test
    fun `a refresh stores new episodes badged as new and downloads nothing`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = "v1", lastModified = null)
        val added = repository.addFromInput("1209828744") as AddPodcastResult.Added

        coEvery { feeds.fetch(podlodkaFeedUrl, "v1", null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a"), feedItem("b")), etag = "v2", lastModified = null)
        val summary = repository.refreshAll(onlyAutoRefreshable = true)

        assertEquals(1, summary.newEpisodeCount)
        assertEquals(1, summary.refreshedCount)

        // The summary has to name what it found: the background refresh's notification builds its
        // text from this list rather than going back to the database.
        val discovered = summary.newEpisodes.single()
        assertEquals("Episode b", discovered.episodeTitle)
        assertEquals("Podlodka Podcast", discovered.podcastTitle)
        assertEquals(added.podcast.id, discovered.podcastId)

        val episodes = repository.observeEpisodes(added.podcast.id).first()
        assertEquals(2, episodes.size)
        val newest = episodes.first { it.guid == "b" }
        assertTrue("Newly discovered episodes must be badged", newest.isNew)
        assertFalse("The old episode must not be re-badged", episodes.first { it.guid == "a" }.isNew)
        assertTrue(
            "A refresh must never download audio",
            episodes.all { it.downloadState == DownloadState.NOT_DOWNLOADED },
        )
    }

    @Test
    fun `a 304 response counts as not modified and changes nothing`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = "v1", lastModified = null)
        val added = repository.addFromInput("1209828744") as AddPodcastResult.Added

        coEvery { feeds.fetch(podlodkaFeedUrl, "v1", null) } returns FeedFetchResult.NotModified
        val summary = repository.refreshAll(onlyAutoRefreshable = false)

        assertEquals(1, summary.notModifiedCount)
        assertEquals(0, summary.newEpisodeCount)
        assertEquals(1, repository.observeEpisodes(added.podcast.id).first().size)
    }

    @Test
    fun `one failing feed does not abort the whole refresh run`() = runTest {
        val otherFeedUrl = "https://example.com/other.rss"
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = null, lastModified = null)
        repository.addFromInput("1209828744")

        coEvery { feeds.fetch(otherFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(
                channel(feedItem("z")).copy(title = "Радио-Т"),
                etag = null,
                lastModified = null,
            )
        repository.addFromInput(otherFeedUrl)

        // Podlodka's host is down; the other feed has a genuinely new episode.
        coEvery { feeds.fetch(podlodkaFeedUrl, any(), any()) } throws IOException("host unreachable")
        coEvery { feeds.fetch(otherFeedUrl, any(), any()) } returns
            FeedFetchResult.Fetched(
                channel(feedItem("z"), feedItem("y")).copy(title = "Радио-Т"),
                etag = null,
                lastModified = null,
            )

        val summary = repository.refreshAll(onlyAutoRefreshable = true)

        assertEquals(listOf("Podlodka Podcast"), summary.failedTitles)
        assertEquals(1, summary.refreshedCount)
        assertEquals(1, summary.newEpisodeCount)
    }

    @Test
    fun `cancelling a refresh run stops it instead of finishing the library`() = runTest {
        // Two shows, so that "the rest of the run" exists to be stopped.
        val otherFeedUrl = "https://example.com/other.rss"
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = null, lastModified = null)
        repository.addFromInput("1209828744")
        coEvery { feeds.fetch(otherFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(
                channel(feedItem("z")).copy(title = "Радио-Т"),
                etag = null,
                lastModified = null,
            )
        repository.addFromInput(otherFeedUrl)

        // Whichever show the run reaches first blocks forever; the run is cancelled while it waits.
        val firstFetchStarted = CompletableDeferred<Unit>()
        val neverAnswers = CompletableDeferred<FeedFetchResult>()
        val refreshFetches = mutableListOf<String>()
        coEvery { feeds.fetch(any(), any(), any()) } coAnswers {
            refreshFetches += firstArg<String>()
            firstFetchStarted.complete(Unit)
            neverAnswers.await()
        }

        val job = launch { repository.refreshAll(onlyAutoRefreshable = false) }
        firstFetchStarted.await()
        job.cancel()
        job.join()

        // The point of the test. `catch (e: Exception)` around the loop body caught the
        // CancellationException, logged it as "this feed failed" and carried on to the second show
        // — so cancelling a library refresh did not actually stop it fetching.
        assertEquals(
            "A cancelled refresh must not keep fetching the remaining feeds",
            1,
            refreshFetches.size,
        )
        assertTrue(job.isCancelled)
    }

    @Test
    fun `auto refresh can be disabled per show`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, any(), any()) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = null, lastModified = null)
        val added = repository.addFromInput("1209828744") as AddPodcastResult.Added

        repository.setAutoRefresh(added.podcast.id, enabled = false)
        val summary = repository.refreshAll(onlyAutoRefreshable = true)

        assertEquals(0, summary.refreshedCount)
        assertEquals(0, summary.notModifiedCount)
    }

    @Test
    fun `removing a show clears its episodes`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, any(), any()) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = null, lastModified = null)
        val added = repository.addFromInput("1209828744") as AddPodcastResult.Added

        repository.remove(added.podcast.id)

        assertTrue(repository.observeLibrary().first().isEmpty())
        assertTrue(repository.observeEpisodes(added.podcast.id).first().isEmpty())
    }

    @Test
    fun `search delegates to apple and wraps failures`() = runTest {
        coEvery { itunes.search("podlodka") } returns listOf(appleResult)
        coEvery { itunes.search("boom") } throws IOException("rate limited")

        assertEquals(listOf(appleResult), repository.search("podlodka").getOrThrow())
        assertTrue(repository.search("boom").isFailure)
    }

    // --- YouTube ----------------------------------------------------------

    @Test
    fun `adds a playlist from a pasted watch link`() = runTest {
        stubPlaylistFetch(youTubeItem("niTJ2221aS8"), youTubeItem("aHsi-OHI_i8"))

        val result = repository.addFromInput(
            "https://www.youtube.com/watch?v=niTJ2221aS8&list=$playlistId",
        )

        assertTrue("Expected Added, got $result", result is AddPodcastResult.Added)
        val added = result as AddPodcastResult.Added
        assertEquals("Generic", added.podcast.title)
        assertEquals("Boris Veriga", added.podcast.author)
        assertEquals(PodcastSource.YOUTUBE, added.podcast.source)
        // The Atom feed URL, never the pasted watch URL: it is the show's identity.
        assertEquals(playlistFeedUrl, added.podcast.feedUrl)
        assertEquals(2, added.episodeCount)
    }

    @Test
    fun `parses a playlist with the youtube parser, not the rss one`() = runTest {
        stubPlaylistFetch(youTubeItem("niTJ2221aS8"))

        repository.addFromInput("https://www.youtube.com/playlist?list=$playlistId")

        coVerify { feeds.fetch(playlistFeedUrl, any(), any(), PodcastSource.YOUTUBE) }
    }

    @Test
    fun `the same playlist pasted two ways is one show`() = runTest {
        // The test this whole canonicalisation design exists for. A watch link and a playlist link
        // name the same thing, and the user should end up with one row rather than two.
        stubPlaylistFetch(youTubeItem("niTJ2221aS8"))
        val first = repository.addFromInput(
            "https://www.youtube.com/watch?v=niTJ2221aS8&list=$playlistId&index=3&t=42s",
        ) as AddPodcastResult.Added

        val second = repository.addFromInput("https://m.youtube.com/playlist?list=$playlistId")

        assertTrue(
            "Expected AlreadyInLibrary, got $second",
            second is AddPodcastResult.AlreadyInLibrary,
        )
        assertEquals(first.podcast.id, (second as AddPodcastResult.AlreadyInLibrary).podcast.id)
        assertEquals(1, repository.observeLibrary().first().size)
    }

    @Test
    fun `stores the audio sentinel rather than a playable url`() = runTest {
        stubPlaylistFetch(youTubeItem("niTJ2221aS8"))

        val added = repository.addFromInput(
            "https://www.youtube.com/playlist?list=$playlistId",
        ) as AddPodcastResult.Added
        val episode = repository.observeEpisodes(added.podcast.id).first().single()

        // A real stream URL would expire within hours, and it doubles as the Media3 cache key, so
        // storing one would leave a downloaded episode unable to find its own cache entry.
        assertEquals("youtube://video/niTJ2221aS8", episode.audioUrl)
        assertEquals(null, episode.durationMs)
    }

    @Test
    fun `refreshing a playlist keeps using the youtube parser`() = runTest {
        stubPlaylistFetch(youTubeItem("niTJ2221aS8"))
        val added = repository.addFromInput(
            "https://www.youtube.com/playlist?list=$playlistId",
        ) as AddPodcastResult.Added
        stubPlaylistFetch(youTubeItem("niTJ2221aS8"), youTubeItem("Eplxom-e1C4"))

        val discovered = repository.refresh(added.podcast.id).getOrThrow()

        assertEquals(1, discovered)
        coVerify { feeds.fetch(playlistFeedUrl, any(), any(), PodcastSource.YOUTUBE) }
    }

    @Test
    fun `a youtube link with no playlist is rejected without a fetch`() = runTest {
        // The most likely user mistake. Falling through to the RSS path would download a watch page
        // and report "Document contains no <channel> element", which helps nobody.
        val result = repository.addFromInput("https://www.youtube.com/watch?v=niTJ2221aS8")

        assertTrue("Expected NotAPlaylist, got $result", result is AddPodcastResult.NotAPlaylist)
        coVerify(exactly = 0) { feeds.fetch(any(), any(), any(), any()) }
    }

    @Test
    fun `a youtube channel link is rejected without a fetch`() = runTest {
        val result = repository.addFromInput("https://www.youtube.com/@somehandle")

        assertTrue("Expected NotAPlaylist, got $result", result is AddPodcastResult.NotAPlaylist)
        coVerify(exactly = 0) { feeds.fetch(any(), any(), any(), any()) }
    }

    @Test
    fun `an ordinary rss feed still parses as rss`() = runTest {
        // The regression guarding every show already in the library.
        coEvery { feeds.fetch(podlodkaFeedUrl, any(), any(), PodcastSource.RSS) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = null, lastModified = null)

        val added = repository.addFromInput(podlodkaFeedUrl) as AddPodcastResult.Added

        assertEquals(PodcastSource.RSS, added.podcast.source)
        coVerify { feeds.fetch(podlodkaFeedUrl, any(), any(), PodcastSource.RSS) }
    }

    // region Staleness

    /**
     * A second repository over the same database, running [minutes] later.
     *
     * The clock is injected as a `Clock.fixed`, so "time passes" is expressed by building another
     * instance rather than by mutating one. The database is shared, so the show added through
     * [repository] is already there with its recorded fetch time.
     */
    private fun repositoryMinutesLater(minutes: Long) = OfflineFirstPodcastRepository(
        podcastDao = database.podcastDao(),
        episodeDao = database.episodeDao(),
        itunes = itunes,
        feeds = feeds,
        autoDownloadScheduler = mockk(relaxed = true),
        clock = Clock.fixed(clock.instant().plus(Duration.ofMinutes(minutes)), ZoneOffset.UTC),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /** Adds Podlodka with one episode, leaving its `lastRefreshAt` at the fixed clock. */
    private suspend fun addPodlodka(): AddPodcastResult.Added {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = "v1", lastModified = null)
        return repository.addFromInput("1209828744") as AddPodcastResult.Added
    }

    @Test
    fun `a feed fetched moments ago is skipped rather than fetched again`() = runTest {
        addPodlodka()

        // Same clock as the add, so the feed is zero minutes old.
        val summary = repository.refreshAll(
            onlyAutoRefreshable = true,
            staleAfter = Duration.ofMinutes(15),
        )

        assertEquals(1, summary.skippedCount)
        assertEquals(0, summary.refreshedCount)
        assertEquals(0, summary.notModifiedCount)
        // The point of the window: no request at all, not a cheap one.
        coVerify(exactly = 0) { feeds.fetch(podlodkaFeedUrl, "v1", any()) }
    }

    @Test
    fun `a feed older than the window is fetched`() = runTest {
        addPodlodka()
        coEvery { feeds.fetch(podlodkaFeedUrl, "v1", null) } returns
            FeedFetchResult.Fetched(
                channel(feedItem("a"), feedItem("b")),
                etag = "v2",
                lastModified = null,
            )

        val summary = repositoryMinutesLater(20).refreshAll(
            onlyAutoRefreshable = true,
            staleAfter = Duration.ofMinutes(15),
        )

        assertEquals(0, summary.skippedCount)
        assertEquals(1, summary.refreshedCount)
        assertEquals(1, summary.newEpisodeCount)
    }

    @Test
    fun `no staleness window means every feed is fetched however recent`() = runTest {
        addPodlodka()
        coEvery { feeds.fetch(podlodkaFeedUrl, "v1", null) } returns FeedFetchResult.NotModified

        // What pull-to-refresh and the background worker both do: ask regardless of age.
        val summary = repository.refreshAll(onlyAutoRefreshable = false)

        assertEquals(0, summary.skippedCount)
        assertEquals(1, summary.notModifiedCount)
    }

    @Test
    fun `a show that has never been refreshed is never considered fresh`() = runTest {
        coEvery { itunes.lookup(any()) } returns appleResult
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns
            FeedFetchResult.Fetched(channel(feedItem("a")), etag = null, lastModified = null)
        val added = repository.addFromInput("1209828744") as AddPodcastResult.Added
        database.podcastDao().upsert(
            database.podcastDao().getById(added.podcast.id)!!.copy(lastRefreshAt = null),
        )
        coEvery { feeds.fetch(podlodkaFeedUrl, null, null) } returns FeedFetchResult.NotModified

        val summary = repository.refreshAll(
            onlyAutoRefreshable = true,
            staleAfter = Duration.ofMinutes(15),
        )

        assertEquals("A show with no recorded fetch has nothing to be fresh from", 0, summary.skippedCount)
        assertEquals(1, summary.notModifiedCount)
    }

    @Test
    fun `refreshing one fresh show reports nothing discovered without fetching`() = runTest {
        val added = addPodlodka()

        val result = repository.refresh(added.podcast.id, staleAfter = Duration.ofMinutes(15))

        assertEquals(0, result.getOrNull())
        coVerify(exactly = 0) { feeds.fetch(podlodkaFeedUrl, "v1", any()) }
    }

    @Test
    fun `refreshing one stale show fetches it`() = runTest {
        val added = addPodlodka()
        coEvery { feeds.fetch(podlodkaFeedUrl, "v1", null) } returns
            FeedFetchResult.Fetched(
                channel(feedItem("a"), feedItem("b")),
                etag = "v2",
                lastModified = null,
            )

        val result = repositoryMinutesLater(20)
            .refresh(added.podcast.id, staleAfter = Duration.ofMinutes(15))

        assertEquals(1, result.getOrNull())
    }

    // endregion
}

package md.borisveriga.bpodcat.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.database.BPodcatDatabase
import md.borisveriga.bpodcat.core.database.model.EpisodeEntity
import md.borisveriga.bpodcat.core.database.model.PodcastEntity
import md.borisveriga.bpodcat.core.model.DownloadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [EpisodeDao], focused on the one behaviour the whole refresh design rests on: a feed
 * refresh must never destroy user state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeDaoTest {

    private lateinit var database: BPodcatDatabase
    private lateinit var episodeDao: EpisodeDao
    private lateinit var podcastDao: PodcastDao

    private val podcast = PodcastEntity(
        id = "podcast-1",
        itunesId = 1209828744L,
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        feedUrl = "https://feeds.soundcloud.com/users/soundcloud:users:291337106/sounds.rss",
        artworkUrl = null,
        description = "",
        addedAt = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episode(
        id: String,
        title: String = "Episode $id",
        publishedAt: Long? = 1_000L,
        durationMs: Long? = 60_000L,
    ) = EpisodeEntity(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = title,
        description = "notes",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = durationMs,
        publishedAt = publishedAt,
        sizeBytes = 1_000L,
        isNew = true,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BPodcatDatabase::class.java,
        ).allowMainThreadQueries().build()
        episodeDao = database.episodeDao()
        podcastDao = database.podcastDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsertFromFeed reports only genuinely new episodes`() = runTest {
        podcastDao.upsert(podcast)

        val firstRun = episodeDao.upsertFromFeed(listOf(episode("a"), episode("b")))
        val secondRun = episodeDao.upsertFromFeed(listOf(episode("a"), episode("b"), episode("c")))

        assertEquals(listOf("a", "b"), firstRun)
        assertEquals(listOf("c"), secondRun)
    }

    @Test
    fun `a refresh preserves playback position played and download state`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a")))
        episodeDao.updatePosition(id = "a", positionMs = 42_000L)
        episodeDao.setPlayed(id = "a", isPlayed = true, positionMs = 42_000L)
        episodeDao.updateDownloadState(
            id = "a",
            state = DownloadState.COMPLETED,
            downloadedBytes = 5_000L,
            percent = 100f,
        )

        // The publisher fixed a typo and re-published the same guid.
        episodeDao.upsertFromFeed(listOf(episode("a", title = "Episode a (fixed)")))

        val stored = checkNotNull(episodeDao.getById("a"))
        assertEquals("Episode a (fixed)", stored.title)
        assertEquals(42_000L, stored.positionMs)
        assertTrue(stored.isPlayed)
        assertEquals(DownloadState.COMPLETED, stored.downloadState)
        assertEquals(5_000L, stored.downloadedBytes)
    }

    @Test
    fun `episodes are observed newest first with undated episodes last`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(
            listOf(
                episode("old", publishedAt = 1_000L),
                episode("new", publishedAt = 9_000L),
                episode("undated", publishedAt = null),
            ),
        )

        val ids = episodeDao.observeByPodcast(podcast.id).first().map { it.id }

        assertEquals(listOf("new", "old", "undated"), ids)
    }

    @Test
    fun `clearNewFlags only affects the given podcast`() = runTest {
        val other = podcast.copy(id = "podcast-2", feedUrl = "https://example.com/other.rss")
        podcastDao.upsert(podcast)
        podcastDao.upsert(other)
        episodeDao.upsertFromFeed(listOf(episode("a")))
        episodeDao.upsertFromFeed(listOf(episode("z").copy(podcastId = other.id)))

        episodeDao.clearNewFlags(podcast.id)

        assertEquals(false, episodeDao.getById("a")?.isNew)
        assertEquals(true, episodeDao.getById("z")?.isNew)
    }

    @Test
    fun `deleting a podcast cascades to its episodes`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a"), episode("b")))

        podcastDao.deleteById(podcast.id)

        assertNull(episodeDao.getById("a"))
        assertNull(episodeDao.getById("b"))
    }

    @Test
    fun `observeDownloaded returns only completed downloads`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a"), episode("b")))
        episodeDao.updateDownloadState("a", DownloadState.COMPLETED, 5_000L, 100f)
        episodeDao.updateDownloadState("b", DownloadState.DOWNLOADING, 2_000L, 40f)

        val downloaded = episodeDao.observeDownloaded().first().map { it.id }

        assertEquals(listOf("a"), downloaded)
    }

    @Test
    fun `downloaded bytes are summed per podcast`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a"), episode("b"), episode("c")))
        episodeDao.updateDownloadState("a", DownloadState.COMPLETED, 5_000L, 100f)
        episodeDao.updateDownloadState("b", DownloadState.COMPLETED, 3_000L, 100f)
        episodeDao.updateDownloadState("c", DownloadState.DOWNLOADING, 1_000L, 20f)

        assertEquals(8_000L, episodeDao.getDownloadedBytes(podcast.id))
    }

    @Test
    fun `a refresh that omits the duration keeps the one the player measured`() = runTest {
        podcastDao.upsert(podcast)
        // The feed publishes no itunes:duration, so the player fills it in while streaming.
        episodeDao.upsertFromFeed(listOf(episode("a", durationMs = null)))
        episodeDao.fillMissingDuration(id = "a", durationMs = 3_600_000L)

        episodeDao.upsertFromFeed(listOf(episode("a", durationMs = null)))

        assertEquals(3_600_000L, checkNotNull(episodeDao.getById("a")).durationMs)
    }

    @Test
    fun `a measured duration never overwrites the publisher's`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a", durationMs = 60_000L)))

        episodeDao.fillMissingDuration(id = "a", durationMs = 3_600_000L)

        assertEquals(60_000L, checkNotNull(episodeDao.getById("a")).durationMs)
    }

    @Test
    fun `a refresh that does publish a duration wins`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a", durationMs = null)))
        episodeDao.fillMissingDuration(id = "a", durationMs = 3_600_000L)

        episodeDao.upsertFromFeed(listOf(episode("a", durationMs = 61_000L)))

        assertEquals(61_000L, checkNotNull(episodeDao.getById("a")).durationMs)
    }

    @Test
    fun `updatePosition leaves the played flag alone`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a")))
        episodeDao.setPlayed(id = "a", isPlayed = true, positionMs = 0L)

        episodeDao.updatePosition(id = "a", positionMs = 5_000L)

        val stored = checkNotNull(episodeDao.getById("a"))
        assertEquals(5_000L, stored.positionMs)
        assertTrue(stored.isPlayed)
    }

    @Test
    fun `observeDownloadsWithShow carries the show on every row`() = runTest {
        podcastDao.upsert(podcast.copy(artworkUrl = "https://art/show.jpg"))
        episodeDao.upsertFromFeed(listOf(episode("a")))
        episodeDao.updateDownloadState("a", DownloadState.COMPLETED, 5_000L, 100f)

        val rows = episodeDao.observeDownloadsWithShow().first()

        assertEquals(listOf("a"), rows.map { it.episode.id })
        assertEquals("Podlodka Podcast", rows.first().showTitle)
        assertEquals("https://art/show.jpg", rows.first().showArtworkUrl)
    }

    @Test
    fun `observeDownloadsWithShow includes transfers and failures, not untouched episodes`() =
        runTest {
            podcastDao.upsert(podcast)
            episodeDao.upsertFromFeed(
                listOf(episode("done"), episode("busy"), episode("waiting"), episode("broken"), episode("untouched")),
            )
            episodeDao.updateDownloadState("done", DownloadState.COMPLETED, 5_000L, 100f)
            episodeDao.updateDownloadState("busy", DownloadState.DOWNLOADING, 2_000L, 40f)
            episodeDao.updateDownloadState("waiting", DownloadState.QUEUED, 0L, 0f)
            episodeDao.updateDownloadState("broken", DownloadState.FAILED, 0L, 0f)

            val rows = episodeDao.observeDownloadsWithShow().first()

            // "untouched" is every other episode in the database; listing it would make this a list
            // of the whole library rather than of downloads.
            assertEquals(
                setOf("done", "busy", "waiting", "broken"),
                rows.map { it.episode.id }.toSet(),
            )
        }

    @Test
    fun `observeDownloadsWithShow puts what needs attention first`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(
            listOf(episode("done"), episode("busy"), episode("waiting"), episode("broken")),
        )
        // Applied in the opposite order to the one expected back, so passing cannot be an accident
        // of insertion order.
        episodeDao.updateDownloadState("done", DownloadState.COMPLETED, 5_000L, 100f)
        episodeDao.updateDownloadState("busy", DownloadState.DOWNLOADING, 2_000L, 40f)
        episodeDao.updateDownloadState("waiting", DownloadState.QUEUED, 0L, 0f)
        episodeDao.updateDownloadState("broken", DownloadState.FAILED, 0L, 0f)

        val rows = episodeDao.observeDownloadsWithShow().first()

        assertEquals(listOf("broken", "busy", "waiting", "done"), rows.map { it.episode.id })
    }

    @Test
    fun `observeDownloadsWithShow orders newest first within a state`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(
            listOf(
                episode("old", publishedAt = 1_000L),
                episode("new", publishedAt = 9_000L),
            ),
        )
        episodeDao.updateDownloadState("old", DownloadState.COMPLETED, 1L, 100f)
        episodeDao.updateDownloadState("new", DownloadState.COMPLETED, 1L, 100f)

        val rows = episodeDao.observeDownloadsWithShow().first()

        assertEquals(listOf("new", "old"), rows.map { it.episode.id })
    }

    @Test
    fun `observeDownloaded still means available offline`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("done"), episode("busy"), episode("broken")))
        episodeDao.updateDownloadState("done", DownloadState.COMPLETED, 5_000L, 100f)
        episodeDao.updateDownloadState("busy", DownloadState.DOWNLOADING, 2_000L, 40f)
        episodeDao.updateDownloadState("broken", DownloadState.FAILED, 0L, 0f)

        // The downloads screen widened; this query must not, because the storage figure, the
        // keep-limit sweep and the player all read "downloaded" as "the audio is on the device".
        assertEquals(listOf("done"), episodeDao.observeDownloaded().first().map { it.id })
    }

    @Test
    fun `observeLatestWithShow returns every show's episodes newest first`() = runTest {
        val other = podcast.copy(
            id = "podcast-2",
            title = "Other Show",
            feedUrl = "https://example.com/other.rss",
        )
        podcastDao.upsert(podcast)
        podcastDao.upsert(other)
        episodeDao.upsertFromFeed(
            listOf(
                episode("mine-old", publishedAt = 1_000L),
                episode("mine-new", publishedAt = 9_000L),
            ),
        )
        episodeDao.upsertFromFeed(
            listOf(episode("theirs", publishedAt = 5_000L).copy(podcastId = other.id)),
        )

        val rows = episodeDao.observeLatestWithShow(limit = 10).first()

        // The whole point of the feed: one chronological list across shows, not a list per show.
        assertEquals(listOf("mine-new", "theirs", "mine-old"), rows.map { it.episode.id })
    }

    @Test
    fun `observeLatestWithShow carries the show on every row`() = runTest {
        podcastDao.upsert(podcast.copy(artworkUrl = "https://art/show.jpg"))
        episodeDao.upsertFromFeed(listOf(episode("a")))

        val row = episodeDao.observeLatestWithShow(limit = 10).first().single()

        assertEquals("Podlodka Podcast", row.showTitle)
        assertEquals("https://art/show.jpg", row.showArtworkUrl)
    }

    @Test
    fun `observeLatestWithShow drops episodes the feed gave no date`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(
            listOf(episode("dated", publishedAt = 1_000L), episode("undated", publishedAt = null)),
        )

        val rows = episodeDao.observeLatestWithShow(limit = 10).first()

        // A chronological feed has nowhere to put an undated episode, and a trailing block of them
        // under "Earlier" would be indistinguishable from genuinely old ones.
        assertEquals(listOf("dated"), rows.map { it.episode.id })
    }

    @Test
    fun `observeLatestWithShow honours the limit and keeps the newest`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(
            (1..5).map { episode("e$it", publishedAt = it * 1_000L) },
        )

        val rows = episodeDao.observeLatestWithShow(limit = 2).first()

        // The limit exists so a large library does not load entirely into memory; it has to cut
        // from the old end, or the feed would be capped at the episodes nobody wants to see.
        assertEquals(listOf("e5", "e4"), rows.map { it.episode.id })
    }

    @Test
    fun `getWithShowByIds joins the show and skips unknown ids`() = runTest {
        podcastDao.upsert(podcast.copy(artworkUrl = "https://art/show.jpg"))
        episodeDao.upsertFromFeed(listOf(episode("a"), episode("b")))

        val rows = episodeDao.getWithShowByIds(listOf("a", "b", "missing"))

        assertEquals(2, rows.size)
        assertEquals(setOf("a", "b"), rows.map { it.episode.id }.toSet())
        assertEquals("Podlodka Podcast", rows.first().showTitle)
        assertEquals("https://art/show.jpg", rows.first().showArtworkUrl)
    }
}

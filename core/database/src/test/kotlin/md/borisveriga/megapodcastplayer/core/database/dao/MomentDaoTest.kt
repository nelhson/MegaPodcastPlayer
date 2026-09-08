package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.MomentEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
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
 * Tests for [MomentDao].
 *
 * Three things are worth pinning here and none of them are visible from the Kotlin: that the joined
 * projection carries the two URLs a share is built from, that a repeated insert at the same spot is
 * refused rather than duplicated, and that removing a show takes its moments with it two levels
 * down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MomentDaoTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var momentDao: MomentDao
    private lateinit var episodeDao: EpisodeDao
    private lateinit var podcastDao: PodcastDao

    private val feedUrl = "https://feeds.simplecast.com/podlodka"
    private val audioUrl = "https://cdn.example.com/42.mp3"
    private val episodeId = "episode-1"

    private val podcast = PodcastEntity(
        id = "podcast-1",
        itunesId = null,
        title = "Podlodka Podcast",
        author = "Podlodka",
        feedUrl = feedUrl,
        artworkUrl = "https://cdn.example.com/art.jpg",
        description = "",
        addedAt = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private val episode = EpisodeEntity(
        id = episodeId,
        podcastId = podcast.id,
        guid = "guid-1",
        title = "Episode 42",
        description = "notes",
        audioUrl = audioUrl,
        artworkUrl = null,
        durationMs = 3_600_000L,
        publishedAt = 1_000L,
        sizeBytes = null,
    )

    private fun moment(positionMs: Long, note: String? = null, createdAt: Long = 1_000L) =
        MomentEntity(
            episodeId = episodeId,
            positionMs = positionMs,
            note = note,
            createdAt = createdAt,
        )

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()
        momentDao = database.momentDao()
        episodeDao = database.episodeDao()
        podcastDao = database.podcastDao()

        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the joined row carries the feed and audio urls a share is built from`() = runTest {
        momentDao.insert(moment(positionMs = 743_000L, note = "the good bit"))

        val row = momentDao.getAllWithEpisode().single()

        assertEquals("Episode 42", row.episodeTitle)
        assertEquals("Podlodka Podcast", row.showTitle)
        assertEquals("https://cdn.example.com/art.jpg", row.showArtworkUrl)
        assertEquals(feedUrl, row.feedUrl)
        assertEquals(audioUrl, row.audioUrl)
        assertEquals("the good bit", row.moment.note)
    }

    @Test
    fun `moments are listed newest first`() = runTest {
        momentDao.insert(moment(positionMs = 10_000L, createdAt = 1_000L))
        momentDao.insert(moment(positionMs = 20_000L, createdAt = 2_000L))

        val positions = momentDao.observeAllWithEpisode().first().map { it.moment.positionMs }

        assertEquals(listOf(20_000L, 10_000L), positions)
    }

    @Test
    fun `a second insert at the same spot is refused rather than duplicated`() = runTest {
        val first = momentDao.insert(moment(positionMs = 743_000L, note = "kept"))
        val second = momentDao.insert(moment(positionMs = 743_000L, note = "would overwrite"))

        assertTrue(first > 0L)
        assertEquals(-1L, second)
        assertEquals("kept", momentDao.getAllWithEpisode().single().moment.note)
    }

    @Test
    fun `a nearby moment is found and a distant one is not`() = runTest {
        momentDao.insert(moment(positionMs = 743_000L))

        assertEquals(743_000L, momentDao.findNear(episodeId, 738_000L, 748_000L)?.positionMs)
        assertNull(momentDao.findNear(episodeId, 100_000L, 200_000L))
    }

    @Test
    fun `the count follows one episode`() = runTest {
        momentDao.insert(moment(positionMs = 10_000L))
        momentDao.insert(moment(positionMs = 20_000L))

        assertEquals(2, momentDao.observeCountForEpisode(episodeId).first())
        assertEquals(0, momentDao.observeCountForEpisode("nobody").first())
    }

    @Test
    fun `a restore writes over the moment already at that spot rather than doubling it`() = runTest {
        momentDao.insert(moment(positionMs = 743_000L, note = "stale"))

        momentDao.restoreAll(listOf(moment(positionMs = 743_000L, note = "from the backup")))

        val rows = momentDao.getAllWithEpisode()
        assertEquals(1, rows.size)
        assertEquals("from the backup", rows.single().moment.note)
    }

    @Test
    fun `a note can be replaced without moving the moment`() = runTest {
        val id = momentDao.insert(moment(positionMs = 743_000L, note = "first"))

        momentDao.updateNote(id, "second")

        val row = momentDao.getAllWithEpisode().single().moment
        assertEquals("second", row.note)
        assertEquals(743_000L, row.positionMs)
    }

    @Test
    fun `removing a show takes its episodes' moments with it`() = runTest {
        momentDao.insert(moment(positionMs = 743_000L))

        podcastDao.deleteById(podcast.id)

        assertEquals(emptyList<Long>(), momentDao.getAllWithEpisode().map { it.moment.id })
    }

    @Test
    fun `the backup projection resolves a moment back to its feed and guid`() = runTest {
        momentDao.insert(moment(positionMs = 743_000L, note = "kept", createdAt = 5_000L))

        val row = momentDao.getBackupRows().single()

        assertEquals(feedUrl, row.feedUrl)
        assertEquals("guid-1", row.guid)
        assertEquals(743_000L, row.positionMs)
        assertEquals("kept", row.note)
        assertEquals(5_000L, row.createdAt)
    }
}

package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the queries a backup and a restore use.
 *
 * These are all about one thing: a backup stores `(feed_url, guid)` pairs rather than row ids, so
 * every read here has to resolve an episode back to the two strings its id was derived from, and
 * every write has to cope with a guid the publisher has since pruned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupQueriesTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var episodeDao: EpisodeDao
    private lateinit var podcastDao: PodcastDao
    private lateinit var queueDao: QueueDao

    private val feedUrl = "https://feeds.simplecast.com/podlodka"

    private val podcast = PodcastEntity(
        id = "podcast-1",
        itunesId = 1_209_828_744L,
        title = "Podlodka Podcast",
        author = "Podlodka",
        feedUrl = feedUrl,
        artworkUrl = null,
        description = "",
        addedAt = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episode(id: String) = EpisodeEntity(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = "Episode $id",
        description = "notes",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = 1_000L,
        sizeBytes = 1_000L,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()
        episodeDao = database.episodeDao()
        podcastDao = database.podcastDao()
        queueDao = database.queueDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `getBackupState reports only episodes the user has touched`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a"), episode("b"), episode("c")))
        episodeDao.updatePosition(id = "a", positionMs = 42_000L)
        episodeDao.setPlayed(id = "b", isPlayed = true, positionMs = 0L)

        val rows = episodeDao.getBackupState().sortedBy { it.guid }

        assertEquals(2, rows.size)
        assertEquals(feedUrl, rows[0].feedUrl)
        assertEquals("guid-a", rows[0].guid)
        assertEquals(42_000L, rows[0].positionMs)
        assertEquals(false, rows[0].isPlayed)
        assertEquals("guid-b", rows[1].guid)
        assertEquals(true, rows[1].isPlayed)
    }

    @Test
    fun `getBackupDownloads reports only completed downloads`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a"), episode("b")))
        episodeDao.updateDownloadState("a", DownloadState.COMPLETED, 5_000L, 100f)
        episodeDao.updateDownloadState("b", DownloadState.DOWNLOADING, 100L, 2f)

        val rows = episodeDao.getBackupDownloads()

        assertEquals(1, rows.size)
        assertEquals("guid-a", rows[0].guid)
        assertEquals(feedUrl, rows[0].feedUrl)
    }

    @Test
    fun `getBackupEntries reports the queue in play order`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a"), episode("b")))
        queueDao.replaceAll(listOf("b", "a"))

        val rows = queueDao.getBackupEntries()

        assertEquals(listOf("guid-b", "guid-a"), rows.map { it.guid })
        assertEquals(listOf(0, 1), rows.map { it.position })
        assertEquals(listOf(feedUrl, feedUrl), rows.map { it.feedUrl })
    }

    @Test
    fun `getExistingIds narrows a derived list to what is actually stored`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a")))

        val existing = episodeDao.getExistingIds(listOf("a", "pruned-by-the-publisher"))

        assertEquals(listOf("a"), existing)
    }

    @Test
    fun `applyRestoredState writes the position and reports one row`() = runTest {
        podcastDao.upsert(podcast)
        episodeDao.upsertFromFeed(listOf(episode("a")))

        val updated = episodeDao.applyRestoredState(id = "a", positionMs = 743_000L, isPlayed = false)

        assertEquals(1, updated)
        assertEquals(743_000L, episodeDao.getById("a")?.positionMs)
    }

    @Test
    fun `applyRestoredState reports zero for a guid the feed no longer carries`() = runTest {
        podcastDao.upsert(podcast)

        val updated = episodeDao.applyRestoredState(id = "gone", positionMs = 1L, isPlayed = true)

        assertEquals(0, updated)
    }

    @Test
    fun `restoreMetadata rewrites only the columns a fresh add cannot know`() = runTest {
        podcastDao.upsert(podcast.copy(itunesId = null, addedAt = 0L))

        podcastDao.restoreMetadata(id = podcast.id, itunesId = 42L, addedAt = 99L)

        val stored = podcastDao.getById(podcast.id)
        assertEquals(42L, stored?.itunesId)
        assertEquals(99L, stored?.addedAt)
        // The feed's own columns are untouched, so a refresh racing the restore cannot lose them.
        assertEquals(podcast.title, stored?.title)
        assertEquals(podcast.feedUrl, stored?.feedUrl)
    }
}

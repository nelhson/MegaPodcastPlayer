package md.borisveriga.megapodcastplayer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.model.MOMENT_MERGE_WINDOW_MS
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DefaultMomentsRepository].
 *
 * Against a real in-memory database rather than a mocked DAO: what this class contributes is the
 * merge window, which is a decision made *from* a query, and a fake DAO would only assert that the
 * query was asked for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultMomentsRepositoryTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var repository: DefaultMomentsRepository
    private lateinit var crashReporter: CrashReporter

    private val savedAt = Instant.parse("2026-09-07T10:00:00Z")
    private val episodeId = "episode-1"

    private val podcast = PodcastEntity(
        id = "podcast-1",
        itunesId = null,
        title = "Podlodka Podcast",
        author = "Podlodka",
        feedUrl = "https://feeds.simplecast.com/podlodka",
        artworkUrl = null,
        description = "",
        addedAt = 1_000L,
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episodeEntity(id: String, audioUrl: String) = EpisodeEntity(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = audioUrl,
        artworkUrl = null,
        durationMs = 3_600_000L,
        publishedAt = 1_000L,
        sizeBytes = null,
    )

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()
        crashReporter = mockk(relaxed = true)
        repository = DefaultMomentsRepository(
            momentDao = database.momentDao(),
            clock = Clock.fixed(savedAt, ZoneOffset.UTC),
            crashReporter = crashReporter,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        database.podcastDao().upsert(podcast)
        database.episodeDao().upsertFromFeed(
            listOf(episodeEntity(episodeId, "https://cdn.example.com/42.mp3")),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a mark is stamped with the clock and comes back whole`() = runTest {
        val saved = repository.mark(episodeId, positionMs = 743_000L, note = "  the good bit  ")

        assertNotNull(saved)
        assertEquals(743_000L, saved?.positionMs)
        assertEquals("the good bit", saved?.note)
        assertEquals(savedAt.toEpochMilli(), saved?.createdAtMs)
    }

    @Test
    fun `a second press within the merge window is the same moment`() = runTest {
        val first = repository.mark(episodeId, positionMs = 743_000L, note = "kept")
        val second = repository.mark(
            episodeId,
            positionMs = 743_000L + MOMENT_MERGE_WINDOW_MS - 1,
            note = "would overwrite",
        )

        assertEquals(first?.id, second?.id)
        // The first note survives: a repeat press is a check, not a correction.
        assertEquals("kept", second?.note)
        assertEquals(1, repository.observeMoments().first().size)
    }

    @Test
    fun `a press well past the window is a moment of its own`() = runTest {
        repository.mark(episodeId, positionMs = 743_000L)
        repository.mark(episodeId, positionMs = 743_000L + MOMENT_MERGE_WINDOW_MS + 1)

        assertEquals(2, repository.observeMoments().first().size)
    }

    @Test
    fun `a negative position clamps to the start rather than being refused`() = runTest {
        assertEquals(0L, repository.mark(episodeId, positionMs = -5_000L)?.positionMs)
    }

    @Test
    fun `marking an episode the database does not hold saves nothing and is reported`() = runTest {
        assertNull(repository.mark("nobody", positionMs = 1_000L))
        assertEquals(emptyList<Long>(), repository.observeMoments().first().map { it.moment.id })
    }

    @Test
    fun `a blank note is stored as no note at all`() = runTest {
        val saved = repository.mark(episodeId, positionMs = 1_000L, note = "first")

        repository.setNote(requireNotNull(saved).id, "   ")

        assertNull(repository.observeMoments().first().single().moment.note)
    }

    @Test
    fun `a deleted moment leaves the list`() = runTest {
        val saved = repository.mark(episodeId, positionMs = 1_000L)

        repository.delete(requireNotNull(saved).id)

        assertEquals(emptyList<Long>(), repository.observeMoments().first().map { it.moment.id })
    }

    @Test
    fun `the count follows the episode being asked about`() = runTest {
        repository.mark(episodeId, positionMs = 1_000L)

        assertEquals(1, repository.observeCountForEpisode(episodeId).first())
        assertEquals(0, repository.observeCountForEpisode(null).first())
    }

    @Test
    fun `the export carries a link built from the episode's own url`() = runTest {
        database.episodeDao().upsertFromFeed(
            listOf(episodeEntity("episode-2", youTubeAudioSentinel("niTJ2221aS8"))),
        )
        repository.mark(episodeId, positionMs = 743_000L, note = "rss")
        repository.mark("episode-2", positionMs = 60_000L, note = "video")

        val document = repository.exportMarkdown()

        assertTrue(document, document.contains("https://cdn.example.com/42.mp3#t=743"))
        assertTrue(document, document.contains("https://www.youtube.com/watch?v=niTJ2221aS8&t=60s"))
        assertTrue(document, document.contains("Feed: <${podcast.feedUrl}>"))
    }

    @Test
    fun `an export with nothing to say writes nothing at all`() = runTest {
        assertEquals("", repository.exportMarkdown())
    }
}

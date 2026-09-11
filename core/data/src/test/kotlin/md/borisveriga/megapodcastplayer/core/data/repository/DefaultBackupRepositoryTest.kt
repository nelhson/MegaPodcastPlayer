package md.borisveriga.megapodcastplayer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile
import md.borisveriga.megapodcastplayer.core.model.backup.BackupPodcast
import md.borisveriga.megapodcastplayer.core.model.episodeIdOf
import md.borisveriga.megapodcastplayer.core.model.podcastIdOf
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
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
 * Tests for [DefaultBackupRepository] against a real in-memory database.
 *
 * The database is real because a fake DAO would only test the fake, and what is under test is the
 * arithmetic that turns a feed URL back into the row id a re-added show lands on.
 * [PodcastRepository] is mocked, but its `addFromInput` is wired to actually insert the show: a
 * restore is a sequence of adds, and the order it applies afterwards depends on them having
 * happened.
 *
 * What is *not* under test any more is listening state, the queue, downloads and moments. Those
 * used to be in the document; a show is a link now, and the tests below say so by their absence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultBackupRepositoryTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var podcastRepository: PodcastRepository
    private lateinit var preferences: UserPreferencesDataSource
    private lateinit var crashReporter: CrashReporter
    private lateinit var repository: DefaultBackupRepository

    private val exportedAt = Instant.parse("2026-09-07T10:00:00Z")

    private val feedA = "https://feeds.example.com/a"
    private val feedB = "https://feeds.example.com/b"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()
        podcastRepository = mockk(relaxed = true)
        preferences = UserPreferencesDataSource(InMemoryPreferencesDataStore())
        crashReporter = mockk(relaxed = true)
        repository = DefaultBackupRepository(
            podcastDao = database.podcastDao(),
            podcastRepository = podcastRepository,
            preferences = preferences,
            clock = Clock.fixed(exportedAt, ZoneOffset.UTC),
            crashReporter = crashReporter,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun podcastEntity(
        feedUrl: String,
        title: String,
        sortOrder: Int = 0,
        source: PodcastSource = PodcastSource.RSS,
    ) = PodcastEntity(
        id = podcastIdOf(feedUrl),
        itunesId = 42L,
        title = title,
        author = "Author",
        feedUrl = feedUrl,
        artworkUrl = null,
        description = "",
        addedAt = 1_000L,
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
        source = source,
        sortOrder = sortOrder,
    )

    private fun episodeEntity(feedUrl: String, guid: String) = EpisodeEntity(
        id = episodeIdOf(podcastIdOf(feedUrl), guid),
        podcastId = podcastIdOf(feedUrl),
        guid = guid,
        title = "Episode $guid",
        description = "notes",
        audioUrl = "https://cdn.example.com/$guid.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = 1_000L,
        sizeBytes = 1_000L,
    )

    /** Makes `addFromInput` behave like the real one: it inserts the show and its episodes. */
    private fun wireAddToInsert(vararg episodeGuids: String) {
        coEvery { podcastRepository.addFromInput(any()) } coAnswers {
            val feedUrl = firstArg<String>()
            database.podcastDao().upsert(podcastEntity(feedUrl, title = "Fetched", sortOrder = 99))
            database.episodeDao()
                .upsertFromFeed(episodeGuids.map { episodeEntity(feedUrl, it) })
            AddPodcastResult.Added(mockk(relaxed = true), episodeGuids.size)
        }
    }

    @Test
    fun `export writes shows in their hand-made order with the injected clock`() = runTest {
        database.podcastDao().upsert(podcastEntity(feedB, "Second", sortOrder = 1))
        database.podcastDao().upsert(podcastEntity(feedA, "First", sortOrder = 0))

        val file = repository.export()

        assertEquals(exportedAt.toEpochMilli(), file.exportedAtMs)
        assertEquals(listOf(feedA, feedB), file.podcasts.map { it.feedUrl })
        assertEquals(listOf(0, 1), file.podcasts.map { it.sortOrder })
    }

    @Test
    fun `export says which shows are YouTube playlists`() = runTest {
        database.podcastDao()
            .upsert(podcastEntity(feedA, "A playlist", source = PodcastSource.YOUTUBE))

        assertEquals(PodcastSource.YOUTUBE, repository.export().podcasts.single().source)
    }

    @Test
    fun `export carries links, not what listening to them produced`() = runTest {
        database.podcastDao().upsert(podcastEntity(feedA, "First"))
        database.episodeDao()
            .upsertFromFeed(listOf(episodeEntity(feedA, "g1"), episodeEntity(feedA, "g2")))
        database.episodeDao().updatePosition(episodeIdOf(podcastIdOf(feedA), "g1"), 5_000L)

        val file = repository.export()

        // One entry for the show, and nothing about the two episodes under it: both come back by
        // fetching the feed again, and a stored copy could only disagree with it.
        assertEquals(listOf(feedA), file.podcasts.map { it.feedUrl })
    }

    @Test
    fun `restore applies the library order after every show has been added`() = runTest {
        wireAddToInsert()

        repository.restore(
            BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(
                    BackupPodcast(feedB, PodcastSource.RSS, "Second", sortOrder = 1),
                    BackupPodcast(feedA, PodcastSource.RSS, "First", sortOrder = 0),
                ),
            ),
        )

        coVerify {
            podcastRepository.reorderLibrary(listOf(podcastIdOf(feedA), podcastIdOf(feedB)))
        }
    }

    @Test
    fun `restore records a failed feed and carries on with the rest`() = runTest {
        coEvery { podcastRepository.addFromInput(feedA) } returns AddPodcastResult.NotFound
        coEvery { podcastRepository.addFromInput(feedB) } coAnswers {
            database.podcastDao().upsert(podcastEntity(feedB, "Second"))
            AddPodcastResult.Added(mockk(relaxed = true), 0)
        }

        val summary = repository.restore(
            BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(
                    BackupPodcast(feedA, PodcastSource.RSS, "First", sortOrder = 0),
                    BackupPodcast(feedB, PodcastSource.RSS, "Second", sortOrder = 1),
                ),
            ),
        )

        assertEquals(1, summary.showsRestored)
        assertEquals(listOf("First"), summary.failedTitles)
    }

    @Test
    fun `restore treats a show already in the library as restored`() = runTest {
        database.podcastDao().upsert(podcastEntity(feedA, "First"))
        coEvery { podcastRepository.addFromInput(feedA) } returns
            AddPodcastResult.AlreadyInLibrary(mockk(relaxed = true))

        val summary = repository.restore(
            BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
            ),
        )

        assertEquals(1, summary.showsRestored)
        assertTrue(summary.failedTitles.isEmpty())
    }

    @Test
    fun `restore leaves an episode of a show already present exactly as it was`() = runTest {
        database.podcastDao().upsert(podcastEntity(feedA, "First"))
        database.episodeDao().upsertFromFeed(listOf(episodeEntity(feedA, "g1")))
        val episodeId = episodeIdOf(podcastIdOf(feedA), "g1")
        database.episodeDao().updatePosition(episodeId, 743_000L)
        coEvery { podcastRepository.addFromInput(feedA) } returns
            AddPodcastResult.AlreadyInLibrary(mockk(relaxed = true))

        repository.restore(
            BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
            ),
        )

        // Importing a list that already names a show the user is halfway through must not be the
        // thing that loses their place in it.
        assertEquals(743_000L, database.episodeDao().getById(episodeId)?.positionMs)
    }

    @Test
    fun `restore reports progress once per show and once at the end`() = runTest {
        wireAddToInsert()
        val seen = mutableListOf<RestoreProgress>()

        repository.restore(
            file = BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(
                    BackupPodcast(feedA, PodcastSource.RSS, "First", sortOrder = 0),
                    BackupPodcast(feedB, PodcastSource.RSS, "Second", sortOrder = 1),
                ),
            ),
            onProgress = { seen += it },
        )

        assertEquals(listOf(0, 1, 2), seen.map { it.completed })
        assertEquals(listOf("First", "Second", ""), seen.map { it.currentTitle })
        assertTrue(seen.all { it.total == 2 })
    }

    @Test
    fun `a cancelled add unwinds the restore rather than being counted as a failure`() = runTest {
        coEvery { podcastRepository.addFromInput(any()) } throws CancellationException("cancelled")

        val thrown = runCatching {
            repository.restore(
                BackupFile(
                    exportedAtMs = 0L,
                    podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
                ),
            )
        }.exceptionOrNull()

        assertTrue("expected the cancellation to propagate", thrown is CancellationException)
    }

    /**
     * The summary of a finished restore is replayed to every new observer for as long as the work
     * that produced it is retained, so which run has already been reported has to be stored.
     */
    @Test
    fun `an acknowledged restore is remembered by id`() = runTest {
        assertNull(repository.observeAcknowledgedRestoreId().first())

        repository.acknowledgeRestore("run-1")

        assertEquals("run-1", repository.observeAcknowledgedRestoreId().first())

        repository.acknowledgeRestore("run-2")

        assertEquals("run-2", repository.observeAcknowledgedRestoreId().first())
    }
}

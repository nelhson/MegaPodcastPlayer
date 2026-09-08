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
import md.borisveriga.megapodcastplayer.core.database.model.MomentEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.backup.BackupDownload
import md.borisveriga.megapodcastplayer.core.model.backup.BackupEpisodeState
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile
import md.borisveriga.megapodcastplayer.core.model.backup.BackupMoment
import md.borisveriga.megapodcastplayer.core.model.backup.BackupPodcast
import md.borisveriga.megapodcastplayer.core.model.backup.BackupQueueEntry
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
 * The database is real because the behaviour under test is largely expressed in SQL and in the
 * arithmetic that turns a `(feedUrl, guid)` pair back into a row id — a fake DAO would only test
 * the fake. [PodcastRepository] is mocked, but its `addFromInput` is wired to actually insert the
 * show, because a restore's whole shape depends on state being applied *after* the add has
 * populated the episodes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultBackupRepositoryTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var podcastRepository: PodcastRepository
    private lateinit var downloadRepository: DownloadRepository
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
        downloadRepository = mockk(relaxed = true)
        preferences = UserPreferencesDataSource(InMemoryPreferencesDataStore())
        crashReporter = mockk(relaxed = true)
        repository = DefaultBackupRepository(
            podcastDao = database.podcastDao(),
            episodeDao = database.episodeDao(),
            queueDao = database.queueDao(),
            momentDao = database.momentDao(),
            podcastRepository = podcastRepository,
            downloadRepository = downloadRepository,
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
    fun `export carries only episodes the user has touched`() = runTest {
        database.podcastDao().upsert(podcastEntity(feedA, "First"))
        database.episodeDao()
            .upsertFromFeed(listOf(episodeEntity(feedA, "g1"), episodeEntity(feedA, "g2")))
        database.episodeDao().updatePosition(episodeIdOf(podcastIdOf(feedA), "g1"), 5_000L)

        val file = repository.export()

        assertEquals(listOf("g1"), file.episodes.map { it.guid })
        assertEquals(5_000L, file.episodes.single().positionMs)
    }

    @Test
    fun `export carries the moments the user wrote`() = runTest {
        database.podcastDao().upsert(podcastEntity(feedA, "First"))
        database.episodeDao().upsertFromFeed(listOf(episodeEntity(feedA, "g1")))
        database.momentDao().insert(
            MomentEntity(
                episodeId = episodeIdOf(podcastIdOf(feedA), "g1"),
                positionMs = 743_000L,
                note = "the good bit",
                createdAt = 5_000L,
            ),
        )

        val file = repository.export()

        val moment = file.moments.single()
        assertEquals(feedA, moment.feedUrl)
        assertEquals("g1", moment.guid)
        assertEquals(743_000L, moment.positionMs)
        assertEquals("the good bit", moment.note)
        assertEquals(5_000L, moment.createdAtMs)
    }

    @Test
    fun `restore writes moments back and counts them`() = runTest {
        wireAddToInsert("g1")

        val summary = repository.restore(
            BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
                moments = listOf(BackupMoment(feedA, "g1", 743_000L, "the good bit", 5_000L)),
            ),
        )

        val stored = database.momentDao().getAllWithEpisode().single().moment
        assertEquals(743_000L, stored.positionMs)
        assertEquals("the good bit", stored.note)
        assertEquals(1, summary.momentsRestored)
    }

    @Test
    fun `restoring the same backup twice leaves one moment, not two`() = runTest {
        wireAddToInsert("g1")
        val file = BackupFile(
            exportedAtMs = 0L,
            podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
            moments = listOf(BackupMoment(feedA, "g1", 743_000L, "the good bit", 5_000L)),
        )

        repository.restore(file)
        repository.restore(file)

        assertEquals(1, database.momentDao().getAllWithEpisode().size)
    }

    @Test
    fun `a moment whose episode the publisher has pruned is dropped rather than throwing`() =
        runTest {
            wireAddToInsert("g1")

            val summary = repository.restore(
                BackupFile(
                    exportedAtMs = 0L,
                    podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
                    moments = listOf(BackupMoment(feedA, "gone", 1L, null, 0L)),
                ),
            )

            assertEquals(0, summary.momentsRestored)
            assertTrue(database.momentDao().getAllWithEpisode().isEmpty())
        }

    @Test
    fun `restore applies listening state by guid`() = runTest {
        wireAddToInsert("g1")

        val summary = repository.restore(
            BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
                episodes = listOf(BackupEpisodeState(feedA, "g1", 743_000L, isPlayed = true)),
            ),
        )

        val stored = database.episodeDao().getById(episodeIdOf(podcastIdOf(feedA), "g1"))
        assertEquals(743_000L, stored?.positionMs)
        assertEquals(true, stored?.isPlayed)
        assertEquals(1, summary.episodesRestored)
        assertEquals(0, summary.episodesMissing)
    }

    @Test
    fun `restore counts state for a guid the publisher has pruned`() = runTest {
        wireAddToInsert("g1")

        val summary = repository.restore(
            BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
                episodes = listOf(BackupEpisodeState(feedA, "gone", 1L, isPlayed = false)),
            ),
        )

        assertEquals(0, summary.episodesRestored)
        assertEquals(1, summary.episodesMissing)
        assertTrue(summary.failedTitles.isEmpty())
    }

    @Test
    fun `restore replaces the queue and drops entries whose episode is missing`() = runTest {
        wireAddToInsert("g1", "g2")

        val summary = repository.restore(
            BackupFile(
                exportedAtMs = 0L,
                podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
                queue = listOf(
                    BackupQueueEntry(feedA, "g2", 0),
                    BackupQueueEntry(feedA, "gone", 1),
                    BackupQueueEntry(feedA, "g1", 2),
                ),
            ),
        )

        assertEquals(2, summary.queueRestored)
        assertEquals(
            listOf("g2", "g1"),
            database.queueDao().getBackupEntries().map { it.guid },
        )
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
    fun `restore re-queues downloads only when asked`() = runTest {
        wireAddToInsert("g1")
        val file = BackupFile(
            exportedAtMs = 0L,
            podcasts = listOf(BackupPodcast(feedA, PodcastSource.RSS, "First")),
            downloads = listOf(BackupDownload(feedA, "g1")),
        )

        val withoutOption = repository.restore(file)

        assertEquals(0, withoutOption.downloadsQueued)
        coVerify(exactly = 0) { downloadRepository.download(any()) }

        coEvery { downloadRepository.download(any()) } returns true
        val withOption = repository.restore(file, RestoreOptions(reDownload = true))

        assertEquals(1, withOption.downloadsQueued)
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

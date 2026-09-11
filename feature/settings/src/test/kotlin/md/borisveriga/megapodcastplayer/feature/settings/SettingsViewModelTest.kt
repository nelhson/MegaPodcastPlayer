package md.borisveriga.megapodcastplayer.feature.settings

import android.net.Uri
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.NoOpCrashReporter
import md.borisveriga.megapodcastplayer.core.data.backup.BackupFileStore
import md.borisveriga.megapodcastplayer.core.data.backup.LibraryRestorer
import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun
import md.borisveriga.megapodcastplayer.core.data.repository.BackupRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreProgress
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreSummary
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.UiPreferencesRepository
import md.borisveriga.megapodcastplayer.core.model.AppearanceSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeSort
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.PodcastWithCounts
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import md.borisveriga.megapodcastplayer.core.model.ThemeChoice
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile
import md.borisveriga.megapodcastplayer.core.model.backup.BackupPodcast
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [SettingsViewModel].
 *
 * The repositories are mocked because none of the behaviour under test is storage behaviour — that
 * is covered where it lives, in `MediaDownloadRepositoryTest` and `UserPreferencesDataSourceTest`.
 * What matters here is that the screen reads one consistent picture and that the two settings the
 * repository takes in pairs are not clobbered when only one of them changes.
 */
// Robolectric only for `android.net.Uri`, which the backup actions take; nothing here touches a
// real activity or a real document provider.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playbackSettings = MutableStateFlow(PlaybackSettings())
    private val downloadSettings = MutableStateFlow(DownloadSettings())
    private val downloadedEpisodes = MutableStateFlow(emptyList<Episode>())

    private val lastBackupAt = MutableStateFlow<Long?>(null)
    private val restoreRun = MutableStateFlow<RestoreRun?>(null)
    private val acknowledgedRestoreId = MutableStateFlow<String?>(null)

    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var downloadRepository: DownloadRepository
    private lateinit var backupRepository: BackupRepository
    private lateinit var backupFileStore: BackupFileStore
    private lateinit var libraryRestorer: LibraryRestorer
    private lateinit var uiPreferences: UiPreferencesRepository
    private lateinit var podcastRepository: PodcastRepository
    private lateinit var showSettingsRepository: ShowSettingsRepository
    private lateinit var viewModel: SettingsViewModel

    private val exportedAt = Instant.parse("2026-09-07T10:00:00Z")

    private val backupFile = BackupFile(
        exportedAtMs = exportedAt.toEpochMilli(),
        podcasts = listOf(
            BackupPodcast("https://feeds.example.com/a", PodcastSource.RSS, "First"),
            BackupPodcast("https://feeds.example.com/b", PodcastSource.RSS, "Second"),
        ),
    )

    private fun downloadedEpisode(id: String, bytes: Long) = Episode(
        id = id,
        podcastId = "podcast-1",
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = Instant.EPOCH,
        sizeBytes = null,
        downloadState = DownloadState.COMPLETED,
        downloadedBytes = bytes,
        downloadPercent = 100f,
    )

    /** Two shows in a folder, the way most other apps export. */
    private val opmlDocument = """
        <opml version="2.0"><body>
          <outline text="Tech">
            <outline type="rss" text="First" xmlUrl="https://feeds.example.com/a" />
            <outline type="rss" text="Second" xmlUrl="https://feeds.example.com/b" />
          </outline>
        </body></opml>
    """.trimIndent()

    private val appearance = MutableStateFlow(AppearanceSettings())
    private val library = MutableStateFlow<List<PodcastWithCounts>>(emptyList())
    private val showSettings = MutableStateFlow<Map<String, ShowSettings>>(emptyMap())

    @Before
    fun setUp() {
        playbackRepository = mockk(relaxed = true)
        downloadRepository = mockk(relaxed = true)
        every { playbackRepository.observePlaybackSettings() } returns playbackSettings
        every { downloadRepository.observeDownloadSettings() } returns downloadSettings
        every { downloadRepository.observeDownloadedEpisodes() } returns downloadedEpisodes
        coEvery { downloadRepository.downloadedBytes() } returns 0L
        backupRepository = mockk(relaxed = true)
        backupFileStore = mockk(relaxed = true)
        libraryRestorer = mockk(relaxed = true)
        every { backupRepository.observeLastBackupAt() } returns lastBackupAt
        every { libraryRestorer.observe() } returns restoreRun
        every { backupRepository.observeAcknowledgedRestoreId() } returns acknowledgedRestoreId
        // Stored rather than merely recorded, so a test can assert what the screen sees next.
        coEvery { backupRepository.acknowledgeRestore(any()) } answers {
            acknowledgedRestoreId.value = firstArg()
        }
        coEvery { backupRepository.export() } returns backupFile
        coEvery { backupFileStore.write(any(), any()) } returns Result.success(Unit)
        uiPreferences = mockk(relaxed = true)
        every { uiPreferences.observeAppearance() } returns appearance
        podcastRepository = mockk(relaxed = true)
        showSettingsRepository = mockk(relaxed = true)
        every { podcastRepository.observeLibrary() } returns library
        every { showSettingsRepository.observeAll() } returns showSettings
        viewModel = SettingsViewModel(
            playbackRepository = playbackRepository,
            downloadRepository = downloadRepository,
            backupRepository = backupRepository,
            backupFileStore = backupFileStore,
            libraryRestorer = libraryRestorer,
            uiPreferences = uiPreferences,
            podcastRepository = podcastRepository,
            showSettingsRepository = showSettingsRepository,
            crashReporter = NoOpCrashReporter,
            clock = Clock.fixed(exportedAt, ZoneOffset.UTC),
        )
    }

    /**
     * SET-6. Until the per-show rate existed this screen held *the* speed; it now holds the speed
     * of every show that has not said otherwise, and a default that never names its exceptions is
     * indistinguishable from a setting being quietly ignored.
     */
    @Test
    fun `the shows that play at their own speed are named, alphabetically`() = runTest {
        library.value = listOf(showWithCounts("b", "Zeitgeist"), showWithCounts("a", "Acquired"))
        showSettings.value = mapOf(
            "a" to ShowSettings(speed = 2f),
            "b" to ShowSettings(speed = 1.5f),
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(
                listOf(ShowSpeedOverride("Acquired", 2f), ShowSpeedOverride("Zeitgeist", 1.5f)),
                state.speedOverrides,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A show that said something other than a speed is not an override of the speed. */
    @Test
    fun `a show with settings but no speed is not listed`() = runTest {
        library.value = listOf(showWithCounts("a", "Acquired"))
        showSettings.value = mapOf("a" to ShowSettings(episodeSort = EpisodeSort.OLDEST_FIRST))

        viewModel.uiState.test {
            assertEquals(emptyList<ShowSpeedOverride>(), awaitItem().speedOverrides)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * An override whose show has been removed would otherwise be drawn as a blank name at a rate:
     * settings outlive the library, because a removed show leaves its preferences entry behind.
     */
    @Test
    fun `an override for a show that has gone is dropped`() = runTest {
        library.value = emptyList()
        showSettings.value = mapOf("a" to ShowSettings(speed = 2f))

        viewModel.uiState.test {
            assertEquals(emptyList<ShowSpeedOverride>(), awaitItem().speedOverrides)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** SET-4. A build with no Firebase configuration reports nothing, and should say so. */
    @Test
    fun `the screen is told whether anything is actually reported`() = runTest {
        viewModel.uiState.test {
            assertFalse(awaitItem().isCrashReporting)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A show in the library, as the settings screen's join over it needs one.
     *
     * @param id the podcast id, which is the key the show's settings are stored under.
     * @param title the show's name, which is the part that gets displayed.
     */
    private fun showWithCounts(id: String, title: String) = PodcastWithCounts(
        podcast = Podcast(
            id = id,
            itunesId = null,
            title = title,
            author = "Someone",
            feedUrl = "https://example.com/$id.rss",
            artworkUrl = null,
            description = "",
            addedAt = Instant.EPOCH,
            lastRefreshAt = null,
            etag = null,
            lastModified = null,
            autoRefresh = true,
        ),
        episodeCount = 0,
        newEpisodeCount = 0,
        downloadedCount = 0,
    )

    /**
     * The theme is the one setting on this screen whose effect is visible while it is chosen, and
     * the activity above the navigation graph reads the same stored value — so it must be stored
     * rather than kept anywhere in the composition.
     */
    @Test
    fun `the stored appearance reaches the screen, and choosing one stores it`() = runTest {
        appearance.value = AppearanceSettings(theme = ThemeChoice.DARK, pureBlack = true)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(ThemeChoice.DARK, state.appearance.theme)
            assertTrue(state.appearance.pureBlack)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.setTheme(ThemeChoice.LIGHT)
        viewModel.setDynamicColor(true)
        viewModel.setPureBlack(false)
        runCurrent()

        coVerify { uiPreferences.setTheme(ThemeChoice.LIGHT) }
        coVerify { uiPreferences.setDynamicColor(true) }
        coVerify { uiPreferences.setPureBlack(false) }
    }

    @Test
    fun `the screen shows both settings groups and the storage summary`() = runTest {
        playbackSettings.value = PlaybackSettings(speed = 1.5f)
        downloadSettings.value = DownloadSettings(autoDownloadNewEpisodes = true)
        downloadedEpisodes.value = listOf(
            downloadedEpisode("a", 5_000_000L),
            downloadedEpisode("b", 7_000_000L),
        )
        coEvery { downloadRepository.downloadedBytes() } returns 12_500_000L

        viewModel.refreshStorageUsage()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1.5f, state.playback.speed, 0.001f)
            assertTrue(state.downloads.autoDownloadNewEpisodes)
            assertEquals(2, state.downloadedEpisodeCount)
            // The cache's own figure, not the sum of the rows: partial downloads and Media3's index
            // occupy storage that no episode row accounts for.
            assertEquals(12_500_000L, state.downloadedBytes)
            assertTrue(state.hasDownloads)
        }
    }

    @Test
    fun `an empty library reports no downloads`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(0, state.downloadedEpisodeCount)
            assertFalse(state.hasDownloads)
        }
    }

    @Test
    fun `changing the skip-ahead interval leaves the skip-back one alone`() = runTest {
        playbackSettings.value = PlaybackSettings(skipForwardMs = 30_000L, skipBackMs = 15_000L)
        // The state flow only produces values while collected, and the view model reads it to fill
        // in the interval it is not changing.
        viewModel.uiState.test { awaitItem() }

        viewModel.setSkipForward(45_000L)

        // The repository takes both at once, so a careless implementation would reset the other to
        // its default here.
        coVerify { playbackRepository.setSkipIntervals(forwardMs = 45_000L, backMs = 15_000L) }
    }

    @Test
    fun `changing the skip-back interval leaves the skip-ahead one alone`() = runTest {
        playbackSettings.value = PlaybackSettings(skipForwardMs = 30_000L, skipBackMs = 15_000L)
        viewModel.uiState.test { awaitItem() }

        viewModel.setSkipBack(5_000L)

        coVerify { playbackRepository.setSkipIntervals(forwardMs = 30_000L, backMs = 5_000L) }
    }

    @Test
    fun `each toggle reaches its repository`() = runTest {
        viewModel.setSpeed(2f)
        viewModel.setAutoPlayNext(false)
        viewModel.setAutoDownload(true)
        viewModel.setUnmeteredOnly(false)
        viewModel.setKeepLimit(5)
        viewModel.setDeleteAfterPlaying(false)

        coVerify { playbackRepository.setSpeed(2f) }
        coVerify { playbackRepository.setAutoPlayNext(false) }
        coVerify { downloadRepository.setAutoDownloadNewEpisodes(true) }
        coVerify { downloadRepository.setUnmeteredOnly(false) }
        coVerify { downloadRepository.setKeepLimitPerPodcast(5) }
        coVerify { downloadRepository.setDeleteAfterPlaying(false) }
    }

    @Test
    fun `removing all downloads reports how much was freed`() = runTest {
        downloadedEpisodes.value = listOf(downloadedEpisode("a", 5_000_000L))
        coEvery { downloadRepository.downloadedBytes() } returns 8_000_000L
        viewModel.refreshStorageUsage()
        viewModel.uiState.test { awaitItem() }

        coEvery { downloadRepository.downloadedBytes() } returns 0L
        viewModel.removeAllDownloads()

        coVerify { downloadRepository.removeAllDownloads() }
        viewModel.uiState.test {
            val state = awaitItem()
            // The figure is captured before the removal — afterwards there is nothing left to
            // measure, and "freed 0 MB" would be a useless confirmation.
            assertEquals(
                SettingsMessage.DownloadsRemoved(8_000_000L),
                state.message,
            )
            assertFalse(state.isRemovingDownloads)
            assertEquals(0L, state.downloadedBytes)
        }
    }

    @Test
    fun `a message is cleared once its snackbar has been shown`() = runTest {
        viewModel.removeAllDownloads()
        viewModel.uiState.test { awaitItem() }

        viewModel.onMessageShown()

        viewModel.uiState.test {
            assertEquals(null, awaitItem().message)
        }
    }

    @Test
    fun `the suggested file name says what it is and carries the injected clock's date`() {
        assertEquals(
            "megapodcastplayer-subscriptions-2026-09-07.opml",
            viewModel.suggestedFileName(),
        )
    }

    @Test
    fun `an export writes OPML and records when it happened`() = runTest {
        // The recording is what the section's "last exported" line reads, and that line is the
        // warning about a database recreated rather than migrated: this file is what puts the
        // shows back, so it is the file whose date is worth showing.
        viewModel.uiState.test {
            awaitItem()
            viewModel.exportTo(Uri.parse("content://documents/subs.opml"))

            coVerify {
                backupFileStore.write(
                    any(),
                    match<String> { it.contains("<opml") && it.contains("https://feeds.example.com/a") },
                )
            }
            coVerify { backupRepository.recordExported(exportedAt.toEpochMilli()) }
            assertEquals(
                SettingsMessage.SubscriptionsExported,
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed export is reported and nothing is recorded`() = runTest {
        coEvery { backupFileStore.write(any(), any()) } returns
            Result.failure(java.io.IOException("no stream"))

        viewModel.uiState.test {
            awaitItem()
            viewModel.exportTo(Uri.parse("content://documents/subs.opml"))

            assertEquals(
                SettingsMessage.SubscriptionsExportFailed,
                expectMostRecentItem().message,
            )
            coVerify(exactly = 0) { backupRepository.recordExported(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a picked subscription list is decoded and held for confirmation`() = runTest {
        coEvery { backupFileStore.read(any()) } returns Result.success(opmlDocument)

        viewModel.uiState.test {
            awaitItem()
            viewModel.prepareImport(Uri.parse("content://documents/subs.opml"))

            val pending = expectMostRecentItem().backup.pendingRestore
            assertEquals(2, pending?.showCount)
            // The folder in the document, reported rather than silently dropped.
            assertEquals(1, pending?.skipped)
            // Nothing is fetched until the user confirms.
            verify(exactly = 0) { libraryRestorer.start(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a file that is not OPML is refused before anything is enqueued`() = runTest {
        coEvery { backupFileStore.read(any()) } returns Result.success("shopping list")

        viewModel.uiState.test {
            awaitItem()
            viewModel.prepareImport(Uri.parse("content://documents/notes.txt"))

            val state = expectMostRecentItem()
            assertEquals(SettingsMessage.SubscriptionsNotRecognised, state.message)
            assertNull(state.backup.pendingRestore)
            verify(exactly = 0) { libraryRestorer.start(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a subscription list with no shows in it says so rather than importing nothing`() = runTest {
        // "The file is empty" and "the file is wrong" are different things to be told, and only one
        // of them is worth going back to the other app for.
        coEvery { backupFileStore.read(any()) } returns
            Result.success("""<opml version="2.0"><body /></opml>""")

        viewModel.uiState.test {
            awaitItem()
            viewModel.prepareImport(Uri.parse("content://documents/empty.opml"))

            val state = expectMostRecentItem()
            assertEquals(SettingsMessage.SubscriptionsEmpty, state.message)
            assertNull(state.backup.pendingRestore)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a document that cannot be read is reported`() = runTest {
        coEvery { backupFileStore.read(any()) } returns
            Result.failure(java.io.IOException("gone"))

        viewModel.uiState.test {
            awaitItem()
            viewModel.prepareImport(Uri.parse("content://documents/subs.opml"))

            assertEquals(
                SettingsMessage.SubscriptionsReadFailed,
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirming hands the decoded subscriptions to the restorer`() = runTest {
        coEvery { backupFileStore.read(any()) } returns Result.success(opmlDocument)

        viewModel.uiState.test {
            awaitItem()
            viewModel.prepareImport(Uri.parse("content://documents/subs.opml"))
            val pending = expectMostRecentItem().backup.pendingRestore
            viewModel.confirmRestore()

            verify { libraryRestorer.start(pending!!.json) }
            assertNull(expectMostRecentItem().backup.pendingRestore)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelling drops the picked document without starting anything`() = runTest {
        coEvery { backupFileStore.read(any()) } returns Result.success(opmlDocument)

        viewModel.uiState.test {
            awaitItem()
            viewModel.prepareImport(Uri.parse("content://documents/subs.opml"))
            expectMostRecentItem()
            viewModel.cancelRestore()

            assertNull(expectMostRecentItem().backup.pendingRestore)
            verify(exactly = 0) { libraryRestorer.start(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the section reports whether the subscriptions have ever been exported`() = runTest {
        viewModel.uiState.test {
            assertNull(awaitItem().backup.lastBackupAtMs)

            lastBackupAt.value = exportedAt.toEpochMilli()

            assertEquals(exportedAt.toEpochMilli(), expectMostRecentItem().backup.lastBackupAtMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a running import makes the section busy`() = runTest {
        viewModel.uiState.test {
            assertFalse(awaitItem().backup.isBusy)

            restoreRun.value = RestoreRun.Running(RestoreProgress(1, 3, "First"))

            assertTrue(expectMostRecentItem().backup.isBusy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The result of a finished restore is retained and replayed by WorkManager for days, so
     * without an acknowledgement the summary dialog greeted the user on every visit to settings.
     */
    @Test
    fun `a finished restore is reported once, then not again`() = runTest {
        viewModel.uiState.test {
            assertNull(awaitItem().backup.restore)

            restoreRun.value = RestoreRun.Finished("run-1", RestoreSummary(showsRestored = 2))

            assertEquals(
                RestoreRun.Finished("run-1", RestoreSummary(showsRestored = 2)),
                expectMostRecentItem().backup.restore,
            )

            viewModel.acknowledgeRestoreResult()

            assertNull(expectMostRecentItem().backup.restore)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { backupRepository.acknowledgeRestore("run-1") }
    }

    @Test
    fun `acknowledging one restore does not silence the next`() = runTest {
        acknowledgedRestoreId.value = "run-1"
        viewModel.uiState.test {
            awaitItem()

            restoreRun.value = RestoreRun.Finished("run-2", RestoreSummary(showsRestored = 1))

            assertEquals(
                RestoreRun.Finished("run-2", RestoreSummary(showsRestored = 1)),
                expectMostRecentItem().backup.restore,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Nothing to acknowledge is not a write: an unfinished run keeps its right to be reported. */
    @Test
    fun `a running restore cannot be acknowledged`() = runTest {
        restoreRun.value = RestoreRun.Running(RestoreProgress(1, 3, "First"))
        viewModel.uiState.test {
            awaitItem()

            viewModel.acknowledgeRestoreResult()

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { backupRepository.acknowledgeRestore(any()) }
    }
}

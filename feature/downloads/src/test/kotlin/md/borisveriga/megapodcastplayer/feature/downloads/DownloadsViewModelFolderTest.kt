package md.borisveriga.megapodcastplayer.feature.downloads

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.backup.BackupFileStore
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DefaultDownloadFolderRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.FolderEdit
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.model.FolderNameProblem
import md.borisveriga.megapodcastplayer.core.model.FolderView
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for the folder half of [DownloadsViewModel]: which folder is on screen, what it costs,
 * and the moves, makes and deletes the folder sheets ask for.
 *
 * The folder repository is the real one over an in-memory store: which folder a download ends up
 * in is the behaviour under test, and a mock would only echo what it was told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelFolderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloads = MutableStateFlow(emptyList<EpisodeWithShow>())

    private lateinit var downloadRepository: DownloadRepository
    private lateinit var folders: DefaultDownloadFolderRepository
    private lateinit var viewModel: DownloadsViewModel

    private fun download(id: String, downloadedBytes: Long = 1_000L) = EpisodeWithShow(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.EPOCH,
            sizeBytes = downloadedBytes,
            downloadState = DownloadState.COMPLETED,
            downloadedBytes = downloadedBytes,
            downloadPercent = 100f,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    @Before
    fun setUp() {
        downloadRepository = mockk(relaxed = true)
        every { downloadRepository.observeDownloads() } returns downloads
        // Combined into the state; a relaxed mock's flow never emits and would freeze it.
        every { downloadRepository.observeVideoDownloads() } returns
            MutableStateFlow<Map<String, VideoDownload>>(emptyMap())
        every { downloadRepository.observeDownloadSettings() } returns MutableStateFlow(DownloadSettings())
        coEvery { downloadRepository.freeBytes() } returns 0L
        folders = DefaultDownloadFolderRepository(UserPreferencesDataSource(InMemoryPreferencesDataStore()))
        viewModel = DownloadsViewModel(
            downloadRepository = downloadRepository,
            folderRepository = folders,
            episodePlayer = mockk<EpisodePlayer>(relaxed = true),
            fileStore = mockk<BackupFileStore>(relaxed = true),
            clock = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun `every folder is shown until one is chosen`() = runTest {
        downloads.value = listOf(download("a"), download("b"))
        folders.moveToFolder(listOf("a"), makeFolder("Commute"))

        viewModel.uiState.test {
            val state = expectMostRecentItem()

            assertEquals(FolderView.AllFolders, state.folderView)
            assertEquals(2, state.sections.flatMap { it.downloads }.size)
            assertNull(state.folderTotals)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `choosing a folder shows what is filed there and its own figures`() = runTest {
        downloads.value = listOf(download("a", downloadedBytes = 1_000L), download("b", downloadedBytes = 2_000L))
        val commute = makeFolder("Commute")
        folders.moveToFolder(listOf("a"), commute)

        viewModel.uiState.test {
            awaitItem()
            viewModel.showFolder(FolderView.Folder(commute))

            val state = expectMostRecentItem()
            assertEquals(listOf("a"), state.sections.flatMap { it.downloads }.map { it.episode.id })
            assertEquals(1_000L, state.folderTotals?.totalBytes)
            // The overall figures still describe the phone.
            assertEquals(3_000L, state.totalBytes)
            assertEquals(mapOf(commute to 1, null to 1), state.folderCounts)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the Downloads folder shows what is filed nowhere else`() = runTest {
        downloads.value = listOf(download("a"), download("b"))
        folders.moveToFolder(listOf("a"), makeFolder("Commute"))

        viewModel.uiState.test {
            awaitItem()
            viewModel.showFolder(FolderView.Downloads)

            assertEquals(listOf("b"), expectMostRecentItem().sections.flatMap { it.downloads }.map { it.episode.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty folder says so rather than looking like a filter`() = runTest {
        downloads.value = listOf(download("a"))
        val commute = makeFolder("Commute")

        viewModel.uiState.test {
            awaitItem()
            viewModel.showFolder(FolderView.Folder(commute))

            val state = expectMostRecentItem()
            assertTrue(state.isFolderEmpty)
            assertTrue(state.sections.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a folder deleted while on screen gives way to every folder`() = runTest {
        downloads.value = listOf(download("a"))
        val commute = makeFolder("Commute")

        viewModel.uiState.test {
            awaitItem()
            viewModel.showFolder(FolderView.Folder(commute))
            folders.deleteFolder(commute)

            assertEquals(FolderView.AllFolders, expectMostRecentItem().folderView)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a move files the download and offers to put it back`() = runTest {
        downloads.value = listOf(download("a"))
        val commute = makeFolder("Commute")

        viewModel.uiState.test {
            awaitItem()
            viewModel.moveToFolder("a", commute)

            assertEquals(
                DownloadsMessage.MovedToFolder(
                    episodeId = "a",
                    title = "Episode a",
                    folderName = "Commute",
                    previousFolderId = null,
                ),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(commute, folders.observeFolders().first().folderOf("a"))

        viewModel.undoMove("a", null)

        assertNull(folders.observeFolders().first().folderOf("a"))
    }

    @Test
    fun `a folder made from a move sheet takes the download with it`() = runTest {
        downloads.value = listOf(download("a"))
        var refusal: FolderNameProblem? = FolderNameProblem.BLANK

        viewModel.uiState.test {
            awaitItem()
            viewModel.createFolder("Lectures", moveEpisodeId = "a") { refusal = it }

            val message = expectMostRecentItem().message as DownloadsMessage.MovedToFolder
            assertEquals("Lectures", message.folderName)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(refusal)
        val stored = folders.observeFolders().first()
        assertEquals(stored.folders.single().id, stored.folderOf("a"))
    }

    @Test
    fun `a refused folder name is reported to the dialog`() = runTest {
        makeFolder("Commute")
        var refusal: FolderNameProblem? = null

        viewModel.createFolder("commute", moveEpisodeId = null) { refusal = it }

        assertEquals(FolderNameProblem.TAKEN, refusal)
    }

    @Test
    fun `deleting a folder alone keeps its downloads on the device`() = runTest {
        downloads.value = listOf(download("a"))
        val commute = makeFolder("Commute")
        folders.moveToFolder(listOf("a"), commute)

        viewModel.uiState.test {
            expectMostRecentItem()
            viewModel.deleteFolder(commute, withDownloads = false)

            assertEquals(DownloadsMessage.FolderDeleted("Commute", 0), expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
        assertNull(folders.observeFolders().first().folderOf("a"))
    }

    @Test
    fun `deleting a folder with its downloads deletes each of them`() = runTest {
        downloads.value = listOf(download("a"), download("b"), download("c"))
        val commute = makeFolder("Commute")
        folders.moveToFolder(listOf("a", "b"), commute)

        viewModel.uiState.test {
            expectMostRecentItem()
            viewModel.deleteFolder(commute, withDownloads = true)

            assertEquals(DownloadsMessage.FolderDeleted("Commute", 2), expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { downloadRepository.removeDownload("a") }
        coVerify { downloadRepository.removeDownload("b") }
        coVerify(exactly = 0) { downloadRepository.removeDownload("c") }
    }

    @Test
    fun `the default folder is stored`() = runTest {
        val commute = makeFolder("Commute")

        viewModel.setDefaultFolder(commute)

        assertEquals(commute, folders.observeFolders().first().defaultFolderId)
    }

    /** Makes a folder through the real repository and returns its id. */
    private suspend fun makeFolder(name: String): String =
        (folders.createFolder(name) as FolderEdit.Done).folder.id
}

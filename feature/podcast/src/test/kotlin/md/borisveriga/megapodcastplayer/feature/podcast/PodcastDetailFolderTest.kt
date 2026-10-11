package md.borisveriga.megapodcastplayer.feature.podcast

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.repository.FolderEdit
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the download folders on the show page: the sheet's chosen folder reaching the
 * download, and every download's message naming the folder it went to — which is all a swipe,
 * having no room to ask, can do to say where.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailFolderTest : PodcastDetailViewModelFixture() {

    /** Makes a folder through the real repository and returns its id. */
    private suspend fun makeFolder(name: String): String =
        (folderRepository.createFolder(name) as FolderEdit.Done).folder.id

    @Test
    fun `the show page carries the user's folders`() = runTest {
        val id = makeFolder("Commute")

        viewModel.uiState.test {
            assertEquals(listOf(id), expectMostRecentItem().downloadFolders.folders.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a swipe into the default folder names it`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        downloadSettings.value = DownloadSettings(unmeteredOnly = false)
        folderRepository.setDefaultFolder(makeFolder("Commute"))
        viewModel.uiState.test { cancelAndIgnoreRemainingEvents() }

        viewModel.uiState.test {
            awaitItem()
            viewModel.swipeDownload("a")
            runCurrent()

            assertEquals(
                PodcastDetailMessage.DownloadQueued("Episode a", waitingForWifi = false, folderName = "Commute"),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a download into Downloads says nothing about folders`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        downloadSettings.value = DownloadSettings(unmeteredOnly = false)
        makeFolder("Commute")

        viewModel.uiState.test {
            awaitItem()
            viewModel.toggleDownload("a")
            runCurrent()

            assertEquals(
                PodcastDetailMessage.DownloadQueued("Episode a", waitingForWifi = false),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the sheet's folder reaches the download and its message`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        downloadSettings.value = DownloadSettings(unmeteredOnly = false)
        val lectures = makeFolder("Lectures")
        val destination = DownloadDestination.Folder(lectures)
        coEvery { downloadRepository.download("a", destination) } returns true

        viewModel.uiState.test {
            awaitItem()
            viewModel.download("a", destination)
            runCurrent()

            assertEquals(
                PodcastDetailMessage.DownloadQueued("Episode a", waitingForWifi = false, folderName = "Lectures"),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { downloadRepository.download("a", destination) }
    }

    @Test
    fun `a video downloaded into a folder names it`() = runTest {
        episodes.value = listOf(
            episode("a", DownloadState.NOT_DOWNLOADED).copy(audioUrl = youTubeAudioSentinel("niTJ2221aS8")),
        )
        downloadSettings.value = DownloadSettings(unmeteredOnly = false)
        val lectures = makeFolder("Lectures")
        val destination = DownloadDestination.Folder(lectures)
        coEvery { downloadRepository.downloadVideo("a", VideoQuality(720), destination) } returns true

        viewModel.uiState.test {
            awaitItem()
            viewModel.downloadVideo("a", VideoQuality(720), destination)
            runCurrent()

            assertEquals(
                PodcastDetailMessage.VideoDownloadQueued("Episode a", VideoQuality(720), folderName = "Lectures"),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}

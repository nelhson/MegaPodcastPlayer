package md.borisveriga.bpodcat.feature.downloads

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.EpisodeWithShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [DownloadsScreen].
 *
 * Two things are worth pinning here. The states a download can be in have to stay *visible* — the
 * screen used to filter everything but finished episodes, so a failure or a transfer waiting for
 * Wi-Fi appeared nowhere at all — and the selection has to act on exactly what was picked, since
 * the action it leads to deletes files.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DownloadsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun download(
        id: String,
        state: DownloadState = DownloadState.COMPLETED,
        downloadPercent: Float = 100f,
    ) = EpisodeWithShow(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
            sizeBytes = 90_000_000L,
            downloadState = state,
            downloadedBytes = if (state == DownloadState.COMPLETED) 90_000_000L else 0L,
            downloadPercent = downloadPercent,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    private fun setScreen(
        downloads: List<EpisodeWithShow>,
        unmeteredOnly: Boolean = true,
        onRemoveSelected: (Set<String>) -> Unit = {},
    ) {
        composeRule.setContent {
            BPodcatTheme {
                DownloadsScreen(
                    uiState = DownloadsUiState(
                        downloads = downloads,
                        completedCount = downloads.count {
                            it.episode.downloadState == DownloadState.COMPLETED
                        },
                        totalBytes = 90_000_000L,
                        freeBytes = 4_000_000_000L,
                        unmeteredOnly = unmeteredOnly,
                        isLoading = false,
                    ),
                    onEpisodeClick = {},
                    onEpisodeQueue = {},
                    onEpisodeRetry = {},
                    onEpisodeRemove = {},
                    onRemoveSelected = onRemoveSelected,
                    onBrowseLibrary = {},
                    onMessageShown = {},
                )
            }
        }
    }

    @Test
    fun `a download waiting for wi-fi says which kind of waiting it is doing`() {
        setScreen(listOf(download("a", state = DownloadState.QUEUED)))

        composeRule.onNodeWithText("Waiting for Wi-Fi").assertExists()
    }

    @Test
    fun `with wi-fi-only off, a queued download says only that it is waiting`() {
        setScreen(listOf(download("a", state = DownloadState.QUEUED)), unmeteredOnly = false)

        composeRule.onNodeWithText("Waiting to download").assertExists()
    }

    @Test
    fun `a failed download is visible and names the way out`() {
        setScreen(listOf(download("a", state = DownloadState.FAILED)))

        composeRule.onNodeWithText("Download failed — tap to try again").assertExists()
    }

    @Test
    fun `a transfer in progress shows how far it has got`() {
        setScreen(
            listOf(download("a", state = DownloadState.DOWNLOADING, downloadPercent = 42f)),
        )

        composeRule.onNodeWithText("Downloading 42%").assertExists()
    }

    @Test
    fun `the storage card draws what is stored against what is left`() {
        setScreen(listOf(download("a")))

        composeRule.onNodeWithText("1 episode · 90 MB").assertExists()
        composeRule.onNodeWithContentDescription("90 MB downloaded, 4.0 GB free").assertExists()
    }

    @Test
    fun `a long press starts a selection and offers what to do with it`() {
        setScreen(listOf(download("a"), download("b")))

        // Nothing is selected to begin with, so the bar is not there.
        composeRule.onNodeWithText("1 selected").assertDoesNotExist()

        composeRule.onNodeWithText("Episode a").performTouchInput { longClick() }

        composeRule.onNodeWithText("1 selected").assertExists()
        composeRule.onNodeWithContentDescription("Remove the selected downloads").assertExists()
    }

    @Test
    fun `removing a selection confirms first, then acts on exactly what was picked`() {
        var removed: Set<String>? = null
        setScreen(listOf(download("a"), download("b")), onRemoveSelected = { removed = it })

        composeRule.onNodeWithText("Episode a").performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Remove the selected downloads").performClick()

        // Deleting files is not undone by a second tap, so it asks — and says what it frees.
        composeRule.onNodeWithText("Remove 1 download?").assertExists()
        assertNull(removed)

        composeRule.onNodeWithText("Remove").performClick()

        assertEquals(setOf("a"), removed)
    }

    @Test
    fun `cancelling the confirmation leaves the downloads alone`() {
        var removed: Set<String>? = null
        setScreen(listOf(download("a")), onRemoveSelected = { removed = it })

        composeRule.onNodeWithText("Episode a").performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Remove the selected downloads").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertNull(removed)
        // The selection survives a cancelled removal: the user changed their mind about deleting,
        // not about what they had picked.
        composeRule.onNodeWithText("1 selected").assertExists()
    }

    @Test
    fun `select all picks up every row`() {
        var removed: Set<String>? = null
        setScreen(
            listOf(download("a"), download("b"), download("c")),
            onRemoveSelected = { removed = it },
        )

        composeRule.onNodeWithText("Episode a").performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Select all").performClick()

        composeRule.onNodeWithText("3 selected").assertExists()

        composeRule.onNodeWithContentDescription("Remove the selected downloads").performClick()
        composeRule.onNodeWithText("Remove").performClick()

        assertEquals(setOf("a", "b", "c"), removed)
    }

    @Test
    fun `clearing the selection puts the row actions back`() {
        setScreen(listOf(download("a")))

        composeRule.onNodeWithText("Episode a").performTouchInput { longClick() }
        // While a selection exists every row means "add me to it", so its own buttons stand down.
        composeRule.onNodeWithContentDescription("Downloaded, remove from device")
            .assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Clear the selection").performClick()

        composeRule.onNodeWithText("1 selected").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Downloaded, remove from device").assertExists()
    }
}

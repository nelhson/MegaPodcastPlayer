package md.borisveriga.bpodcat.feature.downloads

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
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
 * Three things are worth pinning here. The states a download can be in have to stay *visible* — the
 * screen used to filter everything but finished episodes, so a failure or a transfer waiting for
 * Wi-Fi appeared nowhere at all. Removal has to ask before it deletes audio while still calling an
 * unfinished transfer off on the spot. And a row carries no buttons at all: both of the things it
 * can do are tiers of one swipe, queueing on the long pull and removal behind the short one.
 *
 * Both tiers are exercised through their accessibility actions rather than as drags: each is the
 * same handler by construction, and the action is what a screen reader has instead of the gesture.
 * The reorder is exercised both ways, because a long press and a swipe start from the same
 * pointer and it is their separation that is easy to break.
 */
@OptIn(ExperimentalTestApi::class)
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
        onEpisodeRemove: (String) -> Unit = {},
        onEpisodeQueue: (String) -> Unit = {},
        onMove: (List<String>, Int, Int) -> Unit = { _, _, _ -> },
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
                    onEpisodeRetry = {},
                    onEpisodeRemove = onEpisodeRemove,
                    onEpisodeQueue = onEpisodeQueue,
                    onMove = onMove,
                    onRefresh = {},
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
    fun `removing a downloaded episode asks first, and names what it is about to delete`() {
        var removed: String? = null
        setScreen(listOf(download("a"), download("b")), onEpisodeRemove = { removed = it })

        composeRule.onNodeWithText("Episode a").performCustomAccessibilityActionWithLabel("Remove")

        // Deleting audio is not undone by a second gesture, so it asks — and says what it frees.
        composeRule.onNodeWithText("Remove \"Episode a\"?").assertExists()
        assertNull(removed)

        composeRule.onNodeWithText("Remove").performClick()

        assertEquals("a", removed)
    }

    @Test
    fun `cancelling the confirmation leaves the download alone`() {
        var removed: String? = null
        setScreen(listOf(download("a")), onEpisodeRemove = { removed = it })

        composeRule.onNodeWithText("Episode a").performCustomAccessibilityActionWithLabel("Remove")
        composeRule.onNodeWithText("Cancel").performClick()

        assertNull(removed)
        composeRule.onNodeWithText("Remove \"Episode a\"?").assertDoesNotExist()
    }

    @Test
    fun `calling off a transfer does not ask, because nothing is lost by it`() {
        var removed: String? = null
        setScreen(
            listOf(download("a", state = DownloadState.DOWNLOADING, downloadPercent = 42f)),
            onEpisodeRemove = { removed = it },
        )

        composeRule.onNodeWithText("Episode a").performCustomAccessibilityActionWithLabel("Remove")

        assertEquals("a", removed)
        composeRule.onNodeWithText("Remove \"Episode a\"?").assertDoesNotExist()
    }

    @Test
    fun `a row carries no buttons, and the full swipe is the one that queues the episode`() {
        var queued: String? = null
        setScreen(listOf(download("a")), onEpisodeQueue = { queued = it })

        // Nothing is left at the end of the row: the download controls said nothing the rest of
        // the row did not, and the queue button that replaced them is now the swipe.
        composeRule.onNodeWithContentDescription("Downloaded, remove from device")
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Add Episode a to the queue").assertDoesNotExist()

        composeRule.onNodeWithText("Episode a")
            .performCustomAccessibilityActionWithLabel("Add to queue")

        assertEquals("a", queued)
    }

    @Test
    fun `a transfer that has not finished can be queued too`() {
        var queued: String? = null
        setScreen(
            listOf(download("a", state = DownloadState.DOWNLOADING, downloadPercent = 42f)),
            onEpisodeQueue = { queued = it },
        )

        // The ring that used to sit here is gone; the percentage is still on the row, in words.
        composeRule.onNodeWithContentDescription("Downloading, 42%").assertDoesNotExist()
        composeRule.onNodeWithText("Downloading 42%").assertExists()

        composeRule.onNodeWithText("Episode a")
            .performCustomAccessibilityActionWithLabel("Add to queue")

        assertEquals("a", queued)
    }

    @Test
    fun `a row can be reordered without a drag`() {
        var move: Triple<List<String>, Int, Int>? = null
        setScreen(
            listOf(download("a"), download("b")),
            onMove = { ids, from, to -> move = Triple(ids, from, to) },
        )

        composeRule.onNodeWithText("Episode a")
            .performCustomAccessibilityActionWithLabel("Move down")

        assertEquals(Triple(listOf("a", "b"), 0, 1), move)
    }

    @Test
    fun `the first row cannot be moved up`() {
        setScreen(listOf(download("a"), download("b")))

        // No "Move up" action is published for it, so asking for one fails rather than silently
        // doing nothing — which is what makes this assertion meaningful.
        val hasMoveUp = runCatching {
            composeRule.onNodeWithText("Episode a")
                .performCustomAccessibilityActionWithLabel("Move up")
        }.isSuccess

        assertEquals(false, hasMoveUp)
    }

    @Test
    fun `a long press anywhere on a row picks it up, and the drag reorders the list`() {
        var move: Triple<List<String>, Int, Int>? = null
        setScreen(
            listOf(download("a"), download("b")),
            onMove = { ids, from, to -> move = Triple(ids, from, to) },
        )

        composeRule.onNodeWithText("Episode a").performTouchInput {
            down(center)
            // Held past the system's long-press timeout, which is what separates picking the row
            // up from tapping it, swiping it away, or scrolling the list.
            advanceEventTime(LONG_PRESS_MS)
            // One row down puts the dragged row's centre inside the row below it.
            moveBy(Offset(0f, height.toFloat()))
            up()
        }

        assertEquals(Triple(listOf("a", "b"), 0, 1), move)
    }

    private companion object {
        /** Comfortably past `ViewConfiguration`'s 500 ms long-press timeout. */
        const val LONG_PRESS_MS = 1_000L
    }
}

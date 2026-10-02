package md.borisveriga.megapodcastplayer.feature.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the video screen's download button and the download sheet's contents.
 *
 * Behaviour, not pictures: a 24 dp glyph is inside the goldens' tolerance, so whether the button
 * is there, what it says it is, and whether it reaches its handler are asserted here. The sheet's
 * contents are tested without the sheet, which composes into a window of its own.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VideoDownloadControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The video screen on a YouTube episode, with [download] as what the phone holds of it. */
    private fun setScreen(download: VideoDownload?, onOpenDownload: () -> Unit = {}) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                VideoScreen(
                    uiState = VideoUiState(
                        playback = PlaybackState(
                            isConnected = true,
                            episodeId = "e1",
                            title = "Episode",
                            youTubeVideoId = "niTJ2221aS8",
                            durationMs = 60_000L,
                        ),
                        videoDownload = download,
                    ),
                    surface = { modifier -> Box(modifier = modifier) },
                    actions = VideoActions(onOpenDownload = onOpenDownload),
                )
            }
        }
    }

    /** The download sheet's contents over three renditions. */
    private fun setOptions(
        download: VideoDownload?,
        onDownload: (VideoQuality) -> Unit = {},
        onDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                DownloadVideoOptions(
                    qualities = listOf(VideoQuality(360), VideoQuality(720), VideoQuality(1080)),
                    failed = false,
                    download = download,
                    onDownload = onDownload,
                    onDelete = onDelete,
                )
            }
        }
    }

    @Test
    fun `the download button opens the sheet`() {
        var opened = 0
        setScreen(download = null, onOpenDownload = { opened++ })

        composeRule.onNodeWithContentDescription("Download the video").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the button says which quality is on the phone`() {
        setScreen(download = VideoDownload(VideoQuality(1080), DownloadState.COMPLETED, 100f))

        composeRule.onNodeWithContentDescription("Video downloaded at 1080p; tap to change it")
            .assertIsDisplayed()
    }

    @Test
    fun `the button says how far a download has got`() {
        setScreen(download = VideoDownload(VideoQuality(720), DownloadState.DOWNLOADING, 42f))

        composeRule.onNodeWithContentDescription("Downloading the video, 42%; tap to change it")
            .assertIsDisplayed()
    }

    @Test
    fun `a quality tapped in the sheet is the one downloaded`() {
        val picked = mutableListOf<VideoQuality>()
        setOptions(download = null, onDownload = { picked += it })

        composeRule.onNodeWithText("1080p").performClick()

        assertEquals(listOf(VideoQuality(1080)), picked)
    }

    @Test
    fun `with nothing downloaded there is nothing to delete`() {
        setOptions(download = null)

        assertTrue(composeRule.onAllNodesWithTextCount("Delete downloaded video") == 0)
        assertTrue(composeRule.onAllNodesWithTextCount("Cancel download") == 0)
    }

    @Test
    fun `a finished video is marked and offered for deletion`() {
        var deleted = 0
        setOptions(
            download = VideoDownload(VideoQuality(720), DownloadState.COMPLETED, 100f),
            onDelete = { deleted++ },
        )

        composeRule.onNodeWithText("720p").assertIsSelected()
        composeRule.onNodeWithText("Delete downloaded video").performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun `a transfer is called off, not deleted`() {
        setOptions(download = VideoDownload(VideoQuality(720), DownloadState.QUEUED, 0f))

        composeRule.onNodeWithText("Cancel download").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTextCount("Delete downloaded video") == 0)
    }

    /** How many nodes show [text]. */
    private fun ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size
}

package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the one video-download picker: when *Download* has something to do, and what its body
 * offers in each state.
 *
 * The body is tested without its sheet, which composes into a window of its own. The question
 * before a finished file is deleted belongs to the sheet and is pressed by hand on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VideoDownloadSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun download(height: Int, state: DownloadState) =
        VideoDownload(VideoQuality(height), state, percent = 0f)

    /** The body over three renditions, with [download] as what the phone holds. */
    private fun setOptions(
        download: VideoDownload?,
        onDownload: (VideoQuality) -> Unit = {},
        onDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                VideoDownloadOptions(
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
    fun `nothing picked is nothing to download`() {
        assertFalse(canDownloadVideo(picked = null, download = null))
    }

    @Test
    fun `any pick downloads when there is no video yet`() {
        assertTrue(canDownloadVideo(picked = VideoQuality(720), download = null))
    }

    @Test
    fun `the rendition already kept or on its way is not asked for again`() {
        assertFalse(canDownloadVideo(VideoQuality(720), download(720, DownloadState.COMPLETED)))
        assertFalse(canDownloadVideo(VideoQuality(720), download(720, DownloadState.DOWNLOADING)))
    }

    @Test
    fun `another rendition replaces the one kept`() {
        assertTrue(canDownloadVideo(VideoQuality(1080), download(720, DownloadState.COMPLETED)))
    }

    @Test
    fun `a failed download at the same rendition can be retried`() {
        assertTrue(canDownloadVideo(VideoQuality(720), download(720, DownloadState.FAILED)))
    }

    @Test
    fun `a pick is a choice, and Download is the action`() {
        val downloaded = mutableListOf<VideoQuality>()
        setOptions(download = null, onDownload = { downloaded += it })

        composeRule.onNodeWithText("Download").assertIsNotEnabled()
        composeRule.onNodeWithText("1080p").performClick()
        // Picking alone downloads nothing: a tap on a large rendition on mobile data costs nothing.
        assertEquals(emptyList<VideoQuality>(), downloaded)

        composeRule.onNodeWithText("Download").assertIsEnabled().performClick()

        assertEquals(listOf(VideoQuality(1080)), downloaded)
    }

    @Test
    fun `with nothing downloaded there is nothing to delete`() {
        setOptions(download = null)

        assertEquals(0, composeRule.countWithText("Delete downloaded video"))
        assertEquals(0, composeRule.countWithText("Cancel download"))
    }

    @Test
    fun `a finished video starts picked and is offered for deletion`() {
        var deleted = 0
        setOptions(download = download(720, DownloadState.COMPLETED), onDelete = { deleted++ })

        composeRule.onNodeWithText("720p").assertIsSelected()
        composeRule.onNodeWithText("Download").assertIsNotEnabled()
        composeRule.onNodeWithText("Delete downloaded video").performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun `a transfer is called off, not deleted`() {
        setOptions(download = download(720, DownloadState.QUEUED))

        composeRule.onNodeWithText("Cancel download").performClick()
        assertEquals(0, composeRule.countWithText("Delete downloaded video"))
    }

    @Test
    fun `a removal asks first whenever a video goes with it`() {
        val video = download(720, DownloadState.QUEUED)
        // Finished or still arriving, the picture goes with the sound; the one who started a large
        // download a minute ago is the one most likely to be surprised.
        assertTrue(removalTakesVideo(DownloadState.COMPLETED, video))
        assertTrue(removalTakesVideo(DownloadState.DOWNLOADING, video))
        assertTrue(removalTakesVideo(DownloadState.QUEUED, download(720, DownloadState.COMPLETED)))
    }

    @Test
    fun `sound alone, or a tap that downloads, asks nothing`() {
        assertFalse(removalTakesVideo(DownloadState.COMPLETED, video = null))
        // On a failed or missing copy the same tap fetches it again; nothing is removed.
        assertFalse(removalTakesVideo(DownloadState.FAILED, download(720, DownloadState.COMPLETED)))
        assertFalse(removalTakesVideo(DownloadState.NOT_DOWNLOADED, download(720, DownloadState.QUEUED)))
    }

    @Test
    fun `the question names the video and what comes back`() {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                DeleteDownloadDialog(
                    episodeTitle = "Episode a",
                    withVideo = true,
                    freed = "412 MB",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Delete the audio and video of \"Episode a\"?").assertExists()
        composeRule.onNodeWithText(
            "This frees 412 MB. The episode stays in your library and can be downloaded again.",
        ).assertExists()
    }

    @Test
    fun `the download button names the video its delete takes`() {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                DownloadButton(
                    state = DownloadState.COMPLETED,
                    progressPercent = 100f,
                    onClick = {},
                    removesVideo = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Audio and video downloaded, delete both from device")
            .assertExists()
    }

    /** How many nodes show [text]. */
    private fun ComposeContentTestRule.countWithText(text: String): Int =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size
}

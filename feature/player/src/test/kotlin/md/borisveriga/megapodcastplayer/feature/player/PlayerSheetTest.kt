package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.SleepTimerState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [PlayerSheet].
 *
 * The drag itself is arithmetic and is pinned in [PlayerSheetStateTest]. What is worth asserting on
 * the composable is that the two ends of that arithmetic really are two different players drawn
 * from one tree — the bar at 0, the full player at 1 — and that the collapsed bar's own controls
 * still work rather than being swallowed by the tap target that opens the sheet. That last one is
 * the regression a "tap anywhere to expand" surface invites.
 *
 * One real gesture is driven here too. The arithmetic in [PlayerSheetStateTest] was always right;
 * what stranded the sheet half open was the wiring between the gesture and that arithmetic, which
 * only a test that actually swipes can see.
 *
 * Where things sit is asserted too, loosely — halves of the sheet rather than exact offsets. The
 * expanded player is deliberately split top and bottom, and a test that pinned the dp would break
 * on every padding tweak while missing the only thing that matters: which end each block is at.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlayerSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val playing = PlayerUiState(
        playback = PlaybackState(
            isConnected = true,
            episodeId = "e1",
            title = "Podlodka #400",
            showTitle = "Podlodka Podcast",
            isPlaying = true,
            positionMs = 1_200_000L,
            durationMs = 5_025_000L,
            queueEpisodeIds = listOf("e1"),
        ),
        settings = PlaybackSettings(),
    )

    private fun setContent(
        initialValue: PlayerSheetValue,
        uiState: PlayerUiState = playing,
        onPlayPause: () -> Unit = {},
        onSkipForward: () -> Unit = {},
        onOpenSleepTimer: () -> Unit = {},
        onToggleDownload: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ): PlayerSheetState {
        lateinit var sheetState: PlayerSheetState
        composeRule.setContent {
            sheetState = rememberPlayerSheetState(initialValue)
            MegaPodcastPlayerTheme {
                PlayerSheet(
                    uiState = uiState,
                    sheetState = sheetState,
                    onPlayPause = onPlayPause,
                    onSeek = {},
                    onSkipForward = onSkipForward,
                    onSkipBack = {},
                    onSkipToNext = {},
                    onSkipToPrevious = {},
                    onOpenSpeed = {},
                    onOpenSleepTimer = onOpenSleepTimer,
                    onToggleDownload = onToggleDownload,
                    onMarkMoment = {},
                    onOpenMoments = {},
                    onOpenQueue = {},
                    onDismiss = onDismiss,
                )
            }
        }
        return sheetState
    }

    @Test
    fun `collapsed, the sheet is a bar with what is playing and its own controls`() {
        setContent(PlayerSheetValue.Collapsed)

        composeRule.onNodeWithText("Podlodka #400").assertIsDisplayed()
        composeRule.onNodeWithText("Podlodka Podcast").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Skip ahead 30 seconds").assertIsDisplayed()
        // Both directions, not just one: replaying a sentence used to need the sheet opened.
        composeRule.onNodeWithContentDescription("Skip back 30 seconds").assertIsDisplayed()
        // The full player's controls are not merely hidden; they are not composed at all.
        composeRule.onNodeWithContentDescription("Playback position").assertDoesNotExist()
    }

    @Test
    fun `the bar's own buttons still work rather than only opening the sheet`() {
        var paused = false
        var skipped = false
        val sheetState = setContent(
            PlayerSheetValue.Collapsed,
            onPlayPause = { paused = true },
            onSkipForward = { skipped = true },
        )

        composeRule.onNodeWithContentDescription("Pause").performClick()
        composeRule.onNodeWithContentDescription("Skip ahead 30 seconds").performClick()

        assertTrue(paused)
        assertTrue(skipped)
        // And neither tap was also read as "open the player".
        assertEquals(PlayerSheetValue.Collapsed, sheetState.targetValue)
    }

    @Test
    fun `tapping the bar opens the sheet`() {
        val sheetState = setContent(PlayerSheetValue.Collapsed)

        composeRule.onNodeWithText("Podlodka #400").performClick()
        composeRule.waitForIdle()

        assertEquals(PlayerSheetValue.Expanded, sheetState.targetValue)
    }

    @Test
    fun `expanded, the same tree is the full player`() {
        setContent(PlayerSheetValue.Expanded)

        composeRule.onNodeWithText("Podlodka #400").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next episode").assertIsDisplayed()
    }

    /**
     * The top-left button closes the player outright rather than collapsing it: collapsing already
     * has the grabber, a drag and the back gesture, and stopping from the full player used to take
     * two steps.
     */
    @Test
    fun `expanded, the top-left button stops and hides the player`() {
        var dismissed = false
        setContent(PlayerSheetValue.Expanded, onDismiss = { dismissed = true })

        val close = composeRule.onNodeWithContentDescription("Stop playing and hide the player")
        val bounds = close.getBoundsInRoot()
        val root = composeRule.onRoot().getBoundsInRoot()
        // Top-left: in the leading half, and in the header strip above the artwork.
        assertTrue(bounds.right < root.right / 2)
        assertTrue(bounds.bottom < root.bottom / 4)

        close.performClick()

        assertTrue(dismissed)
    }

    @Test
    fun `collapsed, the bar draws no close button of its own`() {
        setContent(PlayerSheetValue.Collapsed)

        // The bar is dismissed by pulling it down; a button there would crowd its transport.
        composeRule.onNodeWithContentDescription("Stop playing and hide the player").assertDoesNotExist()
    }

    /**
     * The controls belong within reach of a thumb, not floating in the middle of the screen with
     * dead space beneath them. They used to follow the title immediately and stop wherever the
     * content ran out.
     */
    @Test
    fun `the controls sit at the bottom and what is playing stays at the top`() {
        setContent(PlayerSheetValue.Expanded)

        val sheetBottom = composeRule.onRoot().getBoundsInRoot().bottom
        // Unmerged: the sheet's own tap-to-expand `clickable` merges every descendant into one
        // node the size of the whole surface, which is the node the collapsed tests click.
        val title = composeRule.onNodeWithText("Podlodka #400", useUnmergedTree = true)
            .getBoundsInRoot()
        val scrubber = composeRule.onNodeWithContentDescription("Playback position")
            .getBoundsInRoot()

        // The title stays in the top half, where the artwork above it lands.
        assertTrue(title.bottom < sheetBottom / 2)
        // The scrubber, and therefore everything under it, has been pushed past the halfway mark.
        assertTrue(scrubber.top > sheetBottom / 2)
    }

    @Test
    fun `the expanded body scrolls rather than clipping at a large font scale`() {
        // The transport controls are the bottom of a fixed-height column above a hero-sized piece
        // of artwork; at 2x they no longer fit, and a column that cannot scroll would simply lose
        // them.
        setContent(PlayerSheetValue.Expanded)

        composeRule.onNodeWithContentDescription("Playback position").assertExists()
        composeRule.onNodeWithContentDescription("Next episode").assertExists()
    }

    @Test
    fun `a drag that stops part-way still settles to an end`() {
        // The sheet has two rest positions and no third. Releasing mid-drag must animate to one of
        // them; parking at the fraction the finger left behind is the bug, and it also desynced
        // the navigation bar, which follows `targetValue` rather than `progress`.
        val sheetState = setContent(PlayerSheetValue.Collapsed)

        composeRule.onNodeWithText("Podlodka #400").performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(PlayerSheetValue.Expanded, sheetState.targetValue)
        assertEquals(1f, sheetState.progress, 0.001f)
    }

    @Test
    fun `expanded, the download button shows the episode's offline state`() {
        setContent(
            PlayerSheetValue.Expanded,
            uiState = playing.copy(
                download = EpisodeDownload(DownloadState.DOWNLOADING, percent = 62f),
            ),
        )

        composeRule.onNodeWithContentDescription("Downloading, 62%").assertIsDisplayed()
    }

    @Test
    fun `expanded, a downloaded episode offers to remove it`() {
        setContent(
            PlayerSheetValue.Expanded,
            uiState = playing.copy(
                download = EpisodeDownload(DownloadState.COMPLETED, percent = 100f),
            ),
        )

        composeRule.onNodeWithContentDescription("Downloaded, delete from device").assertIsDisplayed()
    }

    @Test
    fun `expanded, tapping download reports it`() {
        var taps = 0
        setContent(
            PlayerSheetValue.Expanded,
            uiState = playing.copy(
                download = EpisodeDownload(DownloadState.NOT_DOWNLOADED, percent = 0f),
            ),
            onToggleDownload = { taps++ },
        )

        composeRule.onNodeWithContentDescription("Download").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `with no episode there is no download button to press`() {
        // `playing` carries no download, which is the state before the library has answered.
        setContent(PlayerSheetValue.Expanded)

        composeRule.onNodeWithContentDescription("Download").assertDoesNotExist()
    }

    @Test
    fun `expanded, an idle sleep timer offers itself`() {
        setContent(PlayerSheetValue.Expanded)

        composeRule.onNodeWithContentDescription("Sleep timer").assertIsDisplayed()
    }

    @Test
    fun `expanded, a running sleep timer says how long is left`() {
        // The tint is the fast signal; the description is the one that survives TalkBack and a
        // colour-blind user — and the number in it is what someone lying in the dark wants.
        setContent(
            PlayerSheetValue.Expanded,
            uiState = playing.copy(sleep = SleepTimerState(remainingMs = 18 * 60_000L)),
        )

        composeRule
            .onNodeWithContentDescription("Sleep timer, 18 min left; tap to change it")
            .assertIsDisplayed()
    }

    /**
     * The stop is a place in the episode, but what is wanted is still how long that is. The
     * fixture is twenty minutes into an episode of an hour and twenty-three and three quarters.
     */
    @Test
    fun `expanded, the end-of-episode timer says so, and how far off that is`() {
        setContent(
            PlayerSheetValue.Expanded,
            uiState = playing.copy(sleep = SleepTimerState(isEndOfEpisode = true)),
        )

        composeRule
            .onNodeWithContentDescription(
                "Sleep timer set to the end of this episode, 1 h 3 min left; tap to change it",
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("1 h 3 min", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `expanded, the end-of-chapter timer says so, and how far off that is`() {
        setContent(
            PlayerSheetValue.Expanded,
            uiState = playing.copy(
                sleep = SleepTimerState(endOfChapterIndex = 0),
                chapters = listOf(
                    Chapter(startMs = 0L, title = "Intro"),
                    Chapter(startMs = 3_000_000L, title = "The interview"),
                ),
            ),
        )

        composeRule
            .onNodeWithContentDescription(
                "Sleep timer set to the end of a chapter, 30 min left; tap to change it",
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("30 min", useUnmergedTree = true).assertIsDisplayed()
    }

    /** No length, no number: the button says what it is set to and leaves it there. */
    @Test
    fun `expanded, a stop that cannot be placed shows no number`() {
        setContent(
            PlayerSheetValue.Expanded,
            uiState = playing.copy(
                playback = playing.playback.copy(durationMs = 0L),
                sleep = SleepTimerState(isEndOfEpisode = true),
            ),
        )

        composeRule
            .onNodeWithContentDescription(
                "Sleep timer set to the end of this episode; tap to change it",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `expanded, tapping the sleep timer reports it`() {
        var taps = 0
        setContent(PlayerSheetValue.Expanded, onOpenSleepTimer = { taps++ })

        composeRule.onNodeWithContentDescription("Sleep timer").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `collapsed, neither the sleep timer nor the download button is on the bar`() {
        // The bar is four things wide already; both of these belong to the full player.
        setContent(
            PlayerSheetValue.Collapsed,
            uiState = playing.copy(
                download = EpisodeDownload(DownloadState.NOT_DOWNLOADED, percent = 0f),
            ),
        )

        composeRule.onNodeWithContentDescription("Download").assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription("Sleep timer")
            .assertDoesNotExist()
    }

    /**
     * The inner display, where the sheet has room to set the artwork beside the controls rather
     * than above them. Where things sit is asserted in halves rather than dp, for the reason the
     * stacked case gives: the point is which side each block is on, not the padding around it.
     *
     * The arithmetic that chooses the shape is pinned in [ExpandedPlayerLayoutTest]. What is worth
     * asserting here is that choosing it actually moved anything — the layout swap is a `Row` this
     * composable is capable of drawing with the artwork's half empty and the controls still stacked
     * under it, and only a rendering can say otherwise.
     */
    @Test
    @Config(qualifiers = "w882dp-h830dp-xxhdpi")
    fun `on a wide window the controls take the trailing half`() {
        setContent(PlayerSheetValue.Expanded)

        val middle = composeRule.onRoot().getBoundsInRoot().right / 2
        val title = composeRule.onNodeWithText("Podlodka #400", useUnmergedTree = true)
            .getBoundsInRoot()
        val scrubber = composeRule.onNodeWithContentDescription("Playback position")
            .getBoundsInRoot()

        // Both blocks are past halfway, which is what "beside the artwork" means: the leading half
        // holds nothing but the room the sheet draws its one piece of artwork into.
        assertTrue(title.left > middle)
        assertTrue(scrubber.left > middle)
    }

    /**
     * The other half of the same change. Stacked, the controls are pushed to the bottom edge; side
     * by side there is no bottom edge to push to, and a panel that kept doing it would leave the
     * transport under the artwork's midpoint with a column of empty surface above it.
     */
    @Test
    @Config(qualifiers = "w882dp-h830dp-xxhdpi")
    fun `on a wide window the controls are centred rather than pinned to the bottom`() {
        setContent(PlayerSheetValue.Expanded)

        val sheetBottom = composeRule.onRoot().getBoundsInRoot().bottom
        val upNext = composeRule.onNodeWithText("Up next · nothing queued", useUnmergedTree = true)
            .getBoundsInRoot()

        // The last thing in the panel still ends well clear of the bottom of the window.
        assertTrue(upNext.bottom < sheetBottom * WIDE_PANEL_BOTTOM_CLEARANCE)
    }

    @Test
    @Config(qualifiers = "w882dp-h830dp-xxhdpi")
    fun `on a wide window every control is still there`() {
        // The shape changed; the player did not. Each of these is drawn by a different one of the
        // four blocks the wide panel stacks.
        setContent(PlayerSheetValue.Expanded)

        composeRule.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next episode").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sleep timer").assertIsDisplayed()
        composeRule.onNodeWithText("Up next · nothing queued").assertIsDisplayed()
    }

    private companion object {
        /**
         * How far down the window the wide panel's last row is allowed to reach.
         *
         * Loose on purpose: the assertion is "centred, not pinned", and pinned means flush with the
         * bottom edge.
         */
        const val WIDE_PANEL_BOTTOM_CLEARANCE = 0.9f
    }
}

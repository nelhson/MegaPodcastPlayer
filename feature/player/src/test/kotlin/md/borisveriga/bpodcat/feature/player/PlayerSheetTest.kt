package md.borisveriga.bpodcat.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.media.PlaybackState
import md.borisveriga.bpodcat.core.model.PlaybackSettings
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
        onPlayPause: () -> Unit = {},
        onSkipForward: () -> Unit = {},
    ): PlayerSheetState {
        lateinit var sheetState: PlayerSheetState
        composeRule.setContent {
            sheetState = rememberPlayerSheetState(initialValue)
            BPodcatTheme {
                PlayerSheet(
                    uiState = playing,
                    sheetState = sheetState,
                    onPlayPause = onPlayPause,
                    onSeek = {},
                    onSkipForward = onSkipForward,
                    onSkipBack = {},
                    onSkipToNext = {},
                    onSkipToPrevious = {},
                    onCycleSpeed = {},
                    onOpenQueue = {},
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
        composeRule.onNodeWithContentDescription("Skip ahead").assertIsDisplayed()
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
        composeRule.onNodeWithContentDescription("Skip ahead").performClick()

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
        composeRule.onNodeWithContentDescription("Close the player").assertIsDisplayed()
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
    fun `the close button collapses the sheet rather than navigating anywhere`() {
        val sheetState = setContent(PlayerSheetValue.Expanded)

        composeRule.onNodeWithContentDescription("Close the player").performClick()
        composeRule.waitForIdle()

        assertEquals(PlayerSheetValue.Collapsed, sheetState.targetValue)
    }
}

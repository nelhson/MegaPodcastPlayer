package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the collapsed player bar.
 *
 * Two things are pinned here, and both were bugs the bar shipped with.
 *
 * The first is arithmetic. The bar's height was a flat 64 dp holding two lines of text, so somewhere
 * past a 150 % font scale the lines alone outgrew it and the show's name was clipped in half — on
 * the one surface that is on screen for the whole session. What is asserted is the property rather
 * than a number: whatever the type does at whatever scale, the bar is at least as tall as it. The
 * bar is boxed at exactly `collapsedPlayerHeight()` because that is the geometry the sheet imposes
 * on it, and a bar that measured itself independently would still be clipped by its container.
 *
 * The second is honesty: the skip button was a fixed `Forward30` glyph while the setting offers five
 * intervals, so it said 30 and jumped 45 for four of them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CollapsedPlayerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val playback = PlaybackState(
        isConnected = true,
        episodeId = "e1",
        title = "Podlodka #492",
        showTitle = "Podlodka Podcast",
        isPlaying = true,
        positionMs = 724_000L,
        durationMs = 2_530_000L,
    )

    /**
     * Renders the bar at a given font scale, and reports the height the sheet gave it.
     *
     * @param settings the skip intervals under test.
     * @param fontScale the system text-size multiplier; 2.0 is reachable from Android's own
     *   display settings.
     * @return the bar's height, to assert the content against.
     */
    private fun setContent(
        settings: PlaybackSettings = PlaybackSettings(),
        fontScale: Float = 1f,
    ): Dp {
        var barHeight = Dp.Unspecified
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MegaPodcastPlayerTheme {
                    barHeight = collapsedPlayerHeight()
                    Box(modifier = Modifier.height(barHeight)) {
                        CollapsedPlayer(
                            playback = playback,
                            settings = settings,
                            onPlayPause = {},
                            onSkipBack = {},
                            onSkipForward = {},
                        )
                    }
                }
            }
        }
        return barHeight
    }

    @Test
    fun `both lines of text fit inside the bar`() {
        val barHeight = setContent()

        assertFitsInside(barHeight)
    }

    @Test
    fun `both lines still fit at twice the text size`() {
        // The confirmed break. At 200 % the two lines alone are taller than the 64 dp the bar used
        // to be fixed at, and the show's name was cut through the middle.
        val barHeight = setContent(fontScale = 2f)

        assertTrue("the bar did not grow with the text", barHeight > MIN_BAR_HEIGHT)
        assertFitsInside(barHeight)
    }

    @Test
    fun `the skip buttons say the interval they will actually jump`() {
        setContent(PlaybackSettings(skipForwardMs = 45_000L, skipBackMs = 15_000L))

        composeRule.onNodeWithContentDescription("Skip ahead 45 seconds").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Skip back 15 seconds").assertIsDisplayed()
    }

    @Test
    fun `the bar carries the play control and both skips`() {
        setContent()

        // Three controls, not one. Replaying a sentence used to need the whole sheet opened.
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Skip ahead 30 seconds").assertIsDisplayed()
    }

    /**
     * Asserts that neither line of text runs past the bottom of the bar.
     *
     * @param barHeight the height the bar was measured at.
     */
    private fun assertFitsInside(barHeight: Dp) {
        val title = composeRule.onNodeWithText("Podlodka #492").getBoundsInRoot()
        val show = composeRule.onNodeWithText("Podlodka Podcast").getBoundsInRoot()

        assertTrue("the title overflows the bar", title.bottom <= barHeight)
        assertTrue("the show line overflows the bar", show.bottom <= barHeight)
    }

    private companion object {
        /** The height the bar rests at when the type does not need more. */
        val MIN_BAR_HEIGHT: Dp = 64.dp
    }
}

package md.borisveriga.megapodcastplayer.feature.player.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.testing.SCREENSHOT_QUALIFIERS
import md.borisveriga.megapodcastplayer.core.testing.SCREENSHOT_QUALIFIERS_WIDE
import md.borisveriga.megapodcastplayer.core.testing.ScreenshotVariant
import md.borisveriga.megapodcastplayer.core.testing.captureScreenshot
import md.borisveriga.megapodcastplayer.feature.player.CollapsedPlayerPreview
import md.borisveriga.megapodcastplayer.feature.player.CollapsedPlayerVideoPreview
import md.borisveriga.megapodcastplayer.feature.player.ExpandedPlayerPreview
import md.borisveriga.megapodcastplayer.feature.player.ExpandedPlayerVideoPreview
import md.borisveriga.megapodcastplayer.feature.player.ExpandedPlayerWidePreview
import md.borisveriga.megapodcastplayer.feature.player.ModeSwitchPreview
import md.borisveriga.megapodcastplayer.feature.player.QueueScreenEmptyPreview
import md.borisveriga.megapodcastplayer.feature.player.QueueScreenPreview
import md.borisveriga.megapodcastplayer.feature.player.SkipGlyphsPreview
import md.borisveriga.megapodcastplayer.feature.player.SleepTimerOptionsPreview
import md.borisveriga.megapodcastplayer.feature.player.SpeedControlsPreview
import md.borisveriga.megapodcastplayer.feature.player.video.VideoScreenPreview
import md.borisveriga.megapodcastplayer.feature.player.video.VideoScreenUnavailablePreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the player and the queue.
 *
 * Each test renders the screen's own `@Preview`, for the reason `ComponentScreenshotTest` in
 * `:core:designsystem` explains at length: the preview already holds the curated state, and a
 * second copy of it here would be a second thing to keep true. A state worth guarding that has no
 * preview yet should get one — then it is one line in this file.
 *
 * Three renderings per state: light, dark, and light at 200 % text. The last is the one that
 * catches a fixed height or a single-line title before a device does.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = SCREENSHOT_QUALIFIERS)
class PlayerScreenshotTest(private val variant: ScreenshotVariant) {

    @get:Rule
    val composeRule = createComposeRule()

    /** Renders [content] in the app's theme, on the theme's own ground. */
    private fun capture(name: String, content: @Composable () -> Unit) =
        composeRule.captureScreenshot(name, variant) {
            MegaPodcastPlayerTheme(darkTheme = variant.darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }

    @Test
    fun collapsedPlayer() = capture("collapsed-player") { CollapsedPlayerPreview() }

    /** The bar while an episode is being watched, which differs by one small mark. */
    @Test
    fun collapsedPlayerVideo() = capture("collapsed-player-video") { CollapsedPlayerVideoPreview() }

    @Test
    fun expandedPlayer() = capture("expanded-player") { ExpandedPlayerPreview() }

    /**
     * The player on an episode with a picture: the same sheet with the Audio | Video control in
     * its header.
     *
     * The large-font variant is the one with something to say. The control is as wide as its two
     * labels, and at 200 % it reaches the grabber in the middle of the strip, which gives way.
     */
    @Test
    fun expandedPlayerVideo() = capture("expanded-player-video") { ExpandedPlayerVideoPreview() }

    /**
     * The Audio | Video control alone, once on each face.
     *
     * Which half is selected is told by a fill and a text colour; this is the image that says the
     * two still differ by more than hue, in both schemes.
     */
    @Test
    fun modeSwitch() = capture("mode-switch") { ModeSwitchPreview() }

    /**
     * The one golden recorded on a second device.
     *
     * The suite records at one window size by design, and this is the exception the design was
     * waiting for: PL-11's whole content is a screen rearranged at a second size, and a change to
     * it is invisible in an image of the first. The qualifier is the Fold 7 opened out, which is
     * the window the item was written for.
     */
    @Test
    @Config(qualifiers = SCREENSHOT_QUALIFIERS_WIDE)
    fun expandedPlayerWide() = capture("expanded-player-wide") { ExpandedPlayerWidePreview() }

    @Test
    fun queue() = capture("queue") { QueueScreenPreview() }

    @Test
    fun queueEmpty() = capture("queue-empty") { QueueScreenEmptyPreview() }

    /**
     * The sleep timer's options, with a chapter title far too long for its chip.
     *
     * The large-font variant is the one that matters: this layout once had a chip that did not fit
     * and wrapped until it stood twice the height of its neighbours.
     */
    @Test
    fun sleepTimerOptions() = capture("sleep-timer-options") { SleepTimerOptionsPreview() }

    /**
     * The speed controls at 2×, where the default chip and the selected chip are different chips.
     *
     * What this guards is that the 1× chip stays told apart from the selected one by something
     * other than hue — the two fills are near enough the same lightness that a change to either
     * marking is invisible to every other check in the suite.
     */
    @Test
    fun speedControls() = capture("speed-controls") { SpeedControlsPreview() }

    /**
     * Every skip interval the settings screen offers, both ways round.
     *
     * The numeral is drawn into the arc rather than baked into a vector, so this is the only check
     * that it lands inside it — at 60 as well as at 5, and at 200 % text, where a numeral that took
     * its size from the text scale would burst the glyph holding it.
     */
    @Test
    fun skipGlyphs() = capture("skip-glyphs") { SkipGlyphsPreview() }

    /**
     * The video screen's page shape, with a grey box where the picture would be.
     *
     * The large-font variant is the one that earns it: the title, the show and the transport share
     * the room under a picture whose height is fixed by its proportions, and at 200 % text the
     * question is whether the transport still fits without the title being cut.
     */
    @Test
    fun videoScreen() = capture("video-screen") { VideoScreenPreview() }

    /**
     * The video screen with no picture to show: the poster, the sentence, and its two buttons.
     *
     * The frame's height comes from its proportions, not its contents, so the large-font variant is
     * the check that the sentence and the buttons still fit inside it.
     */
    @Test
    fun videoScreenUnavailable() =
        capture("video-screen-unavailable") { VideoScreenUnavailablePreview() }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = ScreenshotVariant.entries.map { arrayOf(it) }
    }
}

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
import md.borisveriga.megapodcastplayer.feature.player.ExpandedPlayerPreview
import md.borisveriga.megapodcastplayer.feature.player.ExpandedPlayerWidePreview
import md.borisveriga.megapodcastplayer.feature.player.QueueScreenEmptyPreview
import md.borisveriga.megapodcastplayer.feature.player.QueueScreenPreview
import md.borisveriga.megapodcastplayer.feature.player.SleepTimerOptionsPreview
import md.borisveriga.megapodcastplayer.feature.player.SpeedControlsPreview
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

    @Test
    fun expandedPlayer() = capture("expanded-player") { ExpandedPlayerPreview() }

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

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = ScreenshotVariant.entries.map { arrayOf(it) }
    }
}

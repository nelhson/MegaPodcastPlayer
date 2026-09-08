package md.borisveriga.megapodcastplayer.feature.settings.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.testing.SCREENSHOT_QUALIFIERS
import md.borisveriga.megapodcastplayer.core.testing.ScreenshotVariant
import md.borisveriga.megapodcastplayer.core.testing.captureScreenshot
import md.borisveriga.megapodcastplayer.feature.settings.SettingsScreenPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the settings screen.
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
class SettingsScreenshotTest(private val variant: ScreenshotVariant) {

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
    fun settings() = capture("settings") { SettingsScreenPreview() }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = ScreenshotVariant.entries.map { arrayOf(it) }
    }
}

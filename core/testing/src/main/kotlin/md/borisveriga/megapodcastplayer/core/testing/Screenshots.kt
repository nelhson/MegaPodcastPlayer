package md.borisveriga.megapodcastplayer.core.testing

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.RuntimeEnvironment

/**
 * The mechanical half of the screenshot suite: render a composable, write or check its golden.
 *
 * The other half — wrapping the content in `MegaPodcastPlayerTheme` — is deliberately left to the
 * caller. `:core:designsystem`, where the theme lives, depends on nothing that depends on this
 * module and cannot be depended on from here without a cycle (see this module's build file), so
 * every screenshot test carries a four-line `capture` of its own that binds the theme and calls
 * this. That is also the seam that lets a screen record itself inside a `Scaffold` or a sheet
 * rather than on a bare surface.
 *
 * Record with `-Pmegapodcastplayer.screenshots.record`; without it the build verifies, which is
 * what makes a rendering change a failing test rather than a modified file nobody looks at.
 */

/** Where a module's goldens live, relative to the module directory. */
const val SCREENSHOT_DIRECTORY: String = "src/test/screenshots"

/**
 * The device every golden is recorded on.
 *
 * A 411x891dp window is the Fold 7 closed, which is the shape this app is used in most. **mdpi**,
 * one pixel per dp, is a deliberate choice: nothing here is testing subpixel rendering, a golden
 * at that size is still legible as a picture of the layout, and it keeps a suite of a hundred
 * images to a few megabytes rather than tens.
 *
 * Applied with `@Config(qualifiers = SCREENSHOT_QUALIFIERS)` on the test class.
 */
const val SCREENSHOT_QUALIFIERS: String = "w411dp-h891dp-mdpi"

/** Tag on the wrapper whose bounds decide how much of the window ends up in the image. */
private const val CAPTURE_TAG = "megapodcastplayer:capture"

/**
 * How different two renderings have to be before the difference is a failure.
 *
 * Not zero, and the reason is the one thing about this suite that is not about design: Robolectric
 * rasterises text with a Skia built for the machine it runs on, so the same layout comes out with
 * subtly different antialiasing on a Windows laptop and on CI's Linux runner. An exact comparison
 * would fail on every golden with a word in it, for a reason no designer could act on.
 *
 * Two knobs, and they do different jobs. [PIXEL_TOLERANCE] is how far one pixel may move in colour
 * before it counts as changed at all, which is what absorbs a glyph edge landing differently.
 * [CHANGED_PIXEL_TOLERANCE] is how many pixels may then have changed, which is what still catches
 * a row that moved, a colour that was replaced or a string that was rewritten — those move whole
 * regions, not edges.
 *
 * If CI ever disagrees with a local recording by more than this, the answer is to record on the
 * platform CI runs, not to widen these further: past a point the tolerance stops being a fact
 * about renderers and starts hiding the regressions the suite exists to find.
 */
private const val PIXEL_TOLERANCE = 0.02f

/** The fraction of pixels allowed to differ; see [PIXEL_TOLERANCE]. */
private const val CHANGED_PIXEL_TOLERANCE = 0.01f

/**
 * One variant of one screenshot: how the app is being looked at.
 *
 * Three rather than the four a light/dark × 100/200 % grid would give. Dark at 200 % is the one
 * that pays for itself least: the palette and the layout fail independently, and a row that
 * overflows at 200 % overflows in both schemes, so the fourth image would be a second copy of a
 * failure the third already shows.
 *
 * @property suffix what the variant adds to a golden's file name.
 * @property darkTheme whether the dark scheme is in force.
 * @property fontScale the system text size, where 2.0 is the largest Android's display settings
 *   offer and where fixed heights and single-line titles come apart.
 */
enum class ScreenshotVariant(
    val suffix: String,
    val darkTheme: Boolean,
    val fontScale: Float,
) {
    LIGHT("light", darkTheme = false, fontScale = 1f),
    DARK("dark", darkTheme = true, fontScale = 1f),
    LARGE_TEXT("light-200", darkTheme = false, fontScale = 2f),
}

/**
 * Renders [content] and writes, or verifies, one golden.
 *
 * The night qualifier is set before the composition rather than the theme flag being passed in, so
 * that a composable which reads `isSystemInDarkTheme()` itself — which every screen's preview does
 * — renders the scheme the variant asks for without the test having to know that it does.
 *
 * Animations are removed first, through the app's own switch for it: a screenshot of a loop is a
 * picture of one arbitrary frame of it, and the now-playing bars, the wavy hairline, the morphing
 * loader and the scrubber's wave all loop forever. Setting `ANIMATOR_DURATION_SCALE` to zero is
 * what `rememberReduceMotion` already watches, so the goldens are the still frame the app itself
 * draws for someone who has turned animations off — a real rendering rather than a frozen clock.
 *
 * Only the wrapper's bounds are captured, not the window's: a component that is one row tall
 * produces an image one row tall instead of nine hundred pixels of empty background.
 *
 * @param name the golden's name without a variant or an extension, e.g. `episode-row`.
 * @param variant which of the three renderings this is.
 * @param content the composable under test, already wrapped in the app's theme by the caller.
 */
fun ComposeContentTestRule.captureScreenshot(
    name: String,
    variant: ScreenshotVariant,
    content: @Composable () -> Unit,
) {
    Settings.Global.putFloat(
        RuntimeEnvironment.getApplication().contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        0f,
    )
    RuntimeEnvironment.setQualifiers(if (variant.darkTheme) "+night" else "+notnight")
    setContent {
        // Font scale rather than a qualifier because there is no qualifier for it: text size is a
        // setting, not a configuration the resource system selects on.
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, variant.fontScale),
        ) {
            Box(modifier = Modifier.testTag(CAPTURE_TAG)) { content() }
        }
    }
    onNodeWithTag(CAPTURE_TAG).captureRoboImage(
        filePath = "$SCREENSHOT_DIRECTORY/$name-${variant.suffix}.png",
        roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = PIXEL_TOLERANCE),
                changeThreshold = CHANGED_PIXEL_TOLERANCE,
            ),
        ),
    )
}

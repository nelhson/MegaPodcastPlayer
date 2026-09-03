package md.borisveriga.megapodcastplayer.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Applies MegaPodcastPlayer's design system.
 *
 * The look is Material 3 **Expressive** — oversized corner radii, springy motion, shape morphing,
 * a wavy scrubber — but it is built here rather than imported. `MaterialExpressiveTheme`,
 * `MotionScheme` and every Expressive component (`ButtonGroup`, `LoadingIndicator`,
 * `LinearWavyProgressIndicator`, `MaterialShapes`) are `internal` or absent in material3 1.4.0,
 * which is what Compose BOM 2026.08.00 pins; they only become public in the 1.5.0 alphas. Rather
 * than put an alpha in the core UI library of a build that pins every artifact by checksum, the
 * expressive behaviour lives in [megaPodcastPlayerShapes], [Motion] and this module's own components, on top
 * of stable `androidx.graphics:graphics-shapes` for real polygon morphing.
 *
 * There is deliberately **no `dynamicColor` parameter**. It used to exist and default to true,
 * which meant that on `minSdk 34` the wallpaper branch was taken on every real device and the
 * app's own palette rendered nowhere except previews. MegaPodcastPlayer has a brand now; it wears it.
 *
 * Tokens Material has no slot for — semantic colours, spacing, elevation, the tabular-figure type
 * style, the artwork shapes — are provided as composition locals and read through the
 * [MegaPodcastPlayerTheme] object, e.g. `MegaPodcastPlayerTheme.spacing.lg`.
 *
 * @param darkTheme whether to use the dark scheme; follows the system setting by default.
 * @param content the themed content.
 */
@Composable
fun MegaPodcastPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) citronDarkScheme else citronLightScheme
    val extendedColors = if (darkTheme) citronDarkExtendedColors else citronLightExtendedColors

    CompositionLocalProvider(
        LocalMegaPodcastPlayerColors provides extendedColors,
        LocalSpacing provides defaultSpacing,
        LocalElevation provides defaultElevation,
        LocalMegaPodcastPlayerTypeExtras provides megaPodcastPlayerTypeExtras,
        LocalMegaPodcastPlayerShapeExtras provides megaPodcastPlayerShapeExtras,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = megaPodcastPlayerShapes,
            typography = megaPodcastPlayerTypography,
            content = content,
        )
    }
}

/**
 * Accessor for the tokens that sit alongside [MaterialTheme].
 *
 * Deliberately mirrors Material's own shape — `MaterialTheme.colorScheme.primary` and
 * `MegaPodcastPlayerTheme.colors.downloaded` read as two halves of one vocabulary rather than two
 * competing systems.
 */
object MegaPodcastPlayerTheme {

    /** Semantic brand colours Material 3 has no role for. */
    val colors: MegaPodcastPlayerColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMegaPodcastPlayerColors.current

    /** The spacing scale and the named layout constants built on it. */
    val spacing: Spacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current

    /** Named tonal elevation levels. */
    val elevation: Elevation
        @Composable
        @ReadOnlyComposable
        get() = LocalElevation.current

    /** Type roles Material 3 does not define, notably the tabular-figure numeric style. */
    val type: MegaPodcastPlayerTypeExtras
        @Composable
        @ReadOnlyComposable
        get() = LocalMegaPodcastPlayerTypeExtras.current

    /** Shapes with a specific job: artwork, the player sheet, pills. */
    val shapes: MegaPodcastPlayerShapeExtras
        @Composable
        @ReadOnlyComposable
        get() = LocalMegaPodcastPlayerShapeExtras.current
}

// The composition locals are private to this file rather than declared beside their data classes.
// They are an implementation detail of [MegaPodcastPlayerTheme] — nothing outside reads them directly — and
// keeping them private also keeps them out of reach of detekt's top-level camelCase rule, which
// would otherwise force `localMegaPodcastPlayerColors` and break the `Local*` convention every AndroidX
// composition local follows.

private val LocalMegaPodcastPlayerColors = staticCompositionLocalOf { citronDarkExtendedColors }
private val LocalSpacing = staticCompositionLocalOf { defaultSpacing }
private val LocalElevation = staticCompositionLocalOf { defaultElevation }
private val LocalMegaPodcastPlayerTypeExtras = staticCompositionLocalOf { megaPodcastPlayerTypeExtras }
private val LocalMegaPodcastPlayerShapeExtras = staticCompositionLocalOf { megaPodcastPlayerShapeExtras }

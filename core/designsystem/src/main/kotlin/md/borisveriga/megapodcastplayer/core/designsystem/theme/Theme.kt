package md.borisveriga.megapodcastplayer.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
 * [dynamicColor] is **off unless asked for**. It used to exist as a parameter defaulting to true,
 * which meant that on `minSdk 34` the wallpaper branch was taken on every real device and the app's
 * own palette rendered nowhere except previews. MegaPodcastPlayer has a brand and it wears it; the
 * parameter is back only because some people would rather their phone matched itself, and letting
 * them say so costs the default nothing.
 *
 * [pureBlack] applies to the dark palette alone. On the OLED panel this app is built for, a black
 * pixel is a pixel that is off — so it is both a look and, on a long train journey, a battery
 * setting. Only the two ground roles are blackened: the surface *containers* keep their tone, or
 * every card, sheet and settings group would dissolve into the background it is meant to sit on.
 *
 * Tokens Material has no slot for — semantic colours, spacing, elevation, the tabular-figure type
 * style, the artwork shapes — are provided as composition locals and read through the
 * [MegaPodcastPlayerTheme] object, e.g. `MegaPodcastPlayerTheme.spacing.lg`.
 *
 * The system's *Remove animations* setting is read here and published as
 * [MegaPodcastPlayerTheme.reduceMotion], so every hand-rolled loop in the app — the scrubber wave,
 * the refresh hairline, the loader, the now-playing bars — has one place to consult and none of
 * them has to know how the setting is spelled. Compose's animation clock ignores it otherwise.
 *
 * @param darkTheme whether to use the dark scheme; follows the system setting by default.
 * @param dynamicColor whether to derive the Material palette from the wallpaper. The semantic
 *   colours — downloaded, unplayed, now playing — are not derived from it: they mean something, and
 *   a wallpaper has no opinion about what "downloaded" looks like.
 * @param pureBlack whether the dark scheme's two ground colours are true black. Ignored in light.
 * @param content the themed content.
 */
@Composable
fun MegaPodcastPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> citronDarkScheme
        else -> citronLightScheme
    }
    val colorScheme = if (darkTheme && pureBlack) baseScheme.asPureBlack() else baseScheme
    val extendedColors = if (darkTheme) citronDarkExtendedColors else citronLightExtendedColors

    CompositionLocalProvider(
        LocalReduceMotion provides rememberReduceMotion(),
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
 * The same scheme with its two ground colours taken to black.
 *
 * `background` and `surface` only. Everything drawn *on* the ground — the surface containers a card
 * or a sheet uses — keeps its tone, because those tones are the only thing separating a card from
 * the page underneath it, and a settings screen where every group has dissolved is not a darker
 * screen, it is a broken one.
 *
 * @return the scheme to draw with.
 */
private fun ColorScheme.asPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
)

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

    /**
     * True when the user has asked the system to remove animations.
     *
     * Every loop the app drives itself consults this; see [rememberReduceMotion] for why Compose
     * does not honour the setting on its own.
     */
    val reduceMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReduceMotion.current
}

// The composition locals are private to this file rather than declared beside their data classes.
// They are an implementation detail of [MegaPodcastPlayerTheme] — nothing outside reads them directly — and
// keeping them private also keeps them out of reach of detekt's top-level camelCase rule, which
// would otherwise force `localMegaPodcastPlayerColors` and break the `Local*` convention every AndroidX
// composition local follows.

private val LocalMegaPodcastPlayerColors = staticCompositionLocalOf { citronDarkExtendedColors }
private val LocalSpacing = staticCompositionLocalOf { defaultSpacing }

// Not `static`: this one really does change while the app is running, when the setting is turned
// on or off in system settings, and a static local would not invalidate its readers.
private val LocalReduceMotion = compositionLocalOf { false }
private val LocalElevation = staticCompositionLocalOf { defaultElevation }
private val LocalMegaPodcastPlayerTypeExtras = staticCompositionLocalOf { megaPodcastPlayerTypeExtras }
private val LocalMegaPodcastPlayerShapeExtras = staticCompositionLocalOf { megaPodcastPlayerShapeExtras }

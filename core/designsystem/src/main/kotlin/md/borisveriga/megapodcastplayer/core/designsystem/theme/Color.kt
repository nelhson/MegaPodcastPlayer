package md.borisveriga.megapodcastplayer.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * "Citron on ink" — MegaPodcastPlayer's brand palette.
 *
 * An acid yellow-green accent on green-tinted near-black and bone-white neutrals. The neutrals
 * carry a trace of the accent hue so the greys agree with the primary instead of fighting it;
 * a pure grey next to a saturated chartreuse reads as dirty.
 *
 * Both schemes define **every** role. The previous palette declared 8 of ~30 and let the rest
 * fall back to Material's baseline purple, which is how `surfaceVariant`, `outline` and every
 * `surfaceContainer*` — the roles this app leans on hardest — ended up off-brand.
 *
 * Contrast is asserted by `ColorContrastTest`, not assumed: every text pair clears WCAG AA
 * (4.5:1) and every non-text UI pair clears 3:1.
 */

// region Primary — citron

private val Citron10 = Color(0xFF151C00)
private val Citron20 = Color(0xFF232B00)
private val Citron30 = Color(0xFF3A4600)
private val Citron40 = Color(0xFF4A5C00)
private val Citron80 = Color(0xFFBCDA4E)
private val Citron85 = Color(0xFFC9E75C)
private val Citron90 = Color(0xFFD4F24A)
private val Citron95 = Color(0xFFE4FF7A)

// endregion

// region Secondary — muted olive, for supporting chrome

private val Olive10 = Color(0xFF171E07)
private val Olive20 = Color(0xFF2C331B)
private val Olive30 = Color(0xFF424A31)
private val Olive40 = Color(0xFF5A6146)
private val Olive80 = Color(0xFFC2CAA9)
private val Olive90 = Color(0xFFDEE6C6)

// endregion

// region Tertiary — cool teal, deliberately off-hue
//
// Downloads, offline availability and links need to read as "a different kind of thing" from
// the citron play/primary actions. A cool accent does that without introducing a second brand
// colour: it is quiet enough to stay subordinate.

private val Teal10 = Color(0xFF002022)
private val Teal20 = Color(0xFF003739)
private val Teal30 = Color(0xFF1F4D50)
private val Teal40 = Color(0xFF386569)
private val Teal80 = Color(0xFFA0CFD3)
private val Teal90 = Color(0xFFBCEBEF)

// endregion

// region Error — Material baseline red, which is already tuned and universally understood

private val Red10 = Color(0xFF410002)
private val Red20 = Color(0xFF690005)
private val Red30 = Color(0xFF93000A)
private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val Red90 = Color(0xFFFFDAD6)

// endregion

/** Light scheme: ink on bone, citron for anything that acts. */
internal val citronLightScheme: ColorScheme = lightColorScheme(
    primary = Citron40,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Citron85,
    onPrimaryContainer = Citron10,
    inversePrimary = Citron80,

    secondary = Olive40,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Olive90,
    onSecondaryContainer = Olive10,

    tertiary = Teal40,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Teal90,
    onTertiaryContainer = Teal10,

    error = Red40,
    onError = Color(0xFFFFFFFF),
    errorContainer = Red90,
    onErrorContainer = Red10,

    background = Color(0xFFFBFBF3),
    onBackground = Color(0xFF1B1C16),
    surface = Color(0xFFFBFBF3),
    onSurface = Color(0xFF1B1C16),
    surfaceVariant = Color(0xFFE4E4D0),
    onSurfaceVariant = Color(0xFF45483C),
    surfaceDim = Color(0xFFDCDCD3),
    surfaceBright = Color(0xFFFBFBF3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5EE),
    surfaceContainer = Color(0xFFF0F0E8),
    surfaceContainerHigh = Color(0xFFEAEAE2),
    surfaceContainerHighest = Color(0xFFE4E4DD),

    outline = Color(0xFF75786B),
    outlineVariant = Color(0xFFC6C8B8),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303128),
    inverseOnSurface = Color(0xFFF2F1E9),
)

/** Dark scheme: the primary reading of the brand — citron glowing on near-black. */
internal val citronDarkScheme: ColorScheme = darkColorScheme(
    primary = Citron90,
    onPrimary = Citron20,
    primaryContainer = Citron30,
    onPrimaryContainer = Citron95,
    inversePrimary = Citron40,

    secondary = Olive80,
    onSecondary = Olive20,
    secondaryContainer = Olive30,
    onSecondaryContainer = Olive90,

    tertiary = Teal80,
    onTertiary = Teal20,
    tertiaryContainer = Teal30,
    onTertiaryContainer = Teal90,

    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,

    background = Color(0xFF12140D),
    onBackground = Color(0xFFE4E3D9),
    surface = Color(0xFF12140D),
    onSurface = Color(0xFFE4E3D9),
    surfaceVariant = Color(0xFF45483C),
    onSurfaceVariant = Color(0xFFC6C8B8),
    surfaceDim = Color(0xFF12140D),
    surfaceBright = Color(0xFF383A30),
    surfaceContainerLowest = Color(0xFF0D0F09),
    surfaceContainerLow = Color(0xFF1B1C15),
    surfaceContainer = Color(0xFF1F2019),
    surfaceContainerHigh = Color(0xFF292B23),
    surfaceContainerHighest = Color(0xFF34362D),

    outline = Color(0xFF909383),
    outlineVariant = Color(0xFF45483C),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E3D9),
    inverseOnSurface = Color(0xFF303128),
)

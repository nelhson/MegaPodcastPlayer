package md.borisveriga.bpodcat.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import md.borisveriga.bpodcat.core.designsystem.R

/**
 * BPodcat's type system: Bricolage Grotesque for anything that announces, Inter for anything
 * that is read.
 *
 * Both faces are **variable** fonts bundled as single files under `res/font`. `minSdk` is 34, so
 * `FontVariation` is available unconditionally and one file covers every weight — four static
 * weights of Inter would cost roughly twice the bytes for a worse result.
 *
 * The optical-size axis is pinned per family rather than per text style: Bricolage is only ever
 * used large, Inter only ever used small, so a single `opsz` per family lands both in the right
 * part of their design space without needing a family per size.
 */

/** Optical size for the display face, matched to the 24–57sp range it is used at. */
private const val DISPLAY_OPTICAL_SIZE = 48

/** Optical size for the text face, matched to the 11–22sp range it is used at. */
private const val TEXT_OPTICAL_SIZE = 14

private fun bricolage(weight: FontWeight) = Font(
    resId = R.font.bricolage_grotesque,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        // Normal width. Bricolage narrows aggressively if left to the file default on some
        // renderers, and headlines that silently condense look like a bug, not a choice.
        FontVariation.width(100f),
        FontVariation.opticalSizing(DISPLAY_OPTICAL_SIZE.sp),
    ),
)

private fun inter(weight: FontWeight) = Font(
    resId = R.font.inter,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.opticalSizing(TEXT_OPTICAL_SIZE.sp),
    ),
)

/** Display face. Only the two weights the scale actually uses, to keep the family cheap. */
internal val bricolageFamily = FontFamily(
    bricolage(FontWeight.SemiBold),
    bricolage(FontWeight.Bold),
)

/** Text face. */
internal val interFamily = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
)

/**
 * The Material 3 type scale, restated in BPodcat's faces.
 *
 * Display and headline are set in Bricolage with negative tracking — large grotesque type needs
 * tightening or it reads loose. Everything at title size and below is Inter with slightly
 * positive tracking, which is what makes small text on a dark ground stay legible.
 */
internal val bpodcatTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = bricolageFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.02).em,
    ),
    displayMedium = TextStyle(
        fontFamily = bricolageFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.02).em,
    ),
    displaySmall = TextStyle(
        fontFamily = bricolageFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.02).em,
    ),
    headlineLarge = TextStyle(
        fontFamily = bricolageFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.01).em,
    ),
    headlineMedium = TextStyle(
        fontFamily = bricolageFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.01).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = bricolageFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.01).em,
    ),
    titleLarge = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.005.em,
    ),
    bodyMedium = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.005.em,
    ),
    bodySmall = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.005.em,
    ),
    labelLarge = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.01.em,
    ),
    labelMedium = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.01.em,
    ),
    labelSmall = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.01.em,
    ),
)

/**
 * Type roles Material 3 does not define.
 *
 * @property numeric durations, timecodes, file sizes and counts. Tabular figures (`tnum`) so a
 *   ticking clock does not shuffle the glyphs around it every second — the single cheapest
 *   polish win available to a player UI.
 * @property numericLarge the same, at the size the expanded player shows elapsed time.
 */
@Immutable
data class BPodcatTypeExtras(
    val numeric: TextStyle,
    val numericLarge: TextStyle,
)

internal val bpodcatTypeExtras = BPodcatTypeExtras(
    numeric = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.01.em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    numericLarge = TextStyle(
        fontFamily = interFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.01.em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
)

/** OpenType feature tag for fixed-advance figures. */
internal const val TABULAR_FIGURES = "tnum"

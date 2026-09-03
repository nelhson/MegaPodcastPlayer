package md.borisveriga.megapodcastplayer.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contrast of the Citron palette.
 *
 * A palette is the one part of a design system where "it looks fine to me" is not a test: the
 * author is usually on a good screen in a lit room, and the failure mode is invisible to them and
 * disabling for someone else. Every foreground/background pairing the schemes promise is asserted
 * here against the WCAG 2.1 thresholds — 4.5:1 for text, 3:1 for non-text UI.
 *
 * These run on the JVM with no Robolectric: `Color` arithmetic needs no Android runtime.
 */
class ColorContrastTest {

    @Test
    fun `light scheme text pairs meet WCAG AA`() {
        assertTextPairs(citronLightScheme, "light")
    }

    @Test
    fun `dark scheme text pairs meet WCAG AA`() {
        assertTextPairs(citronDarkScheme, "dark")
    }

    @Test
    fun `light scheme UI pairs meet WCAG AA for non-text`() {
        assertUiPairs(citronLightScheme, "light")
    }

    @Test
    fun `dark scheme UI pairs meet WCAG AA for non-text`() {
        assertUiPairs(citronDarkScheme, "dark")
    }

    @Test
    fun `light extended colors are legible on their own backgrounds`() {
        assertExtendedPairs(citronLightExtendedColors, citronLightScheme, "light")
    }

    @Test
    fun `dark extended colors are legible on their own backgrounds`() {
        assertExtendedPairs(citronDarkExtendedColors, citronDarkScheme, "dark")
    }

    /**
     * The schemes must be fully specified.
     *
     * The palette this replaced defined 8 of ~30 roles and silently inherited Material's baseline
     * purple for the rest — including `surfaceVariant` and every `surfaceContainer*`, which is
     * most of what the app actually draws. Catching a re-introduction of that is worth a test:
     * baseline purple is a distinctive hue and nothing in the Citron palette comes near it.
     */
    @Test
    fun `no role falls back to the Material baseline purple`() {
        listOf(citronLightScheme to "light", citronDarkScheme to "dark").forEach { (scheme, name) ->
            scheme.namedRoles().forEach { (role, color) ->
                assertTrue(
                    "$name.$role is ${color.hex()}, which is a purple hue — it looks unset",
                    !color.isPurple(),
                )
            }
        }
    }

    private fun assertTextPairs(scheme: ColorScheme, name: String) {
        val pairs = listOf(
            Triple("onSurface/surface", scheme.onSurface, scheme.surface),
            Triple("onBackground/background", scheme.onBackground, scheme.background),
            Triple("onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface),
            Triple("onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant),
            Triple("onPrimary/primary", scheme.onPrimary, scheme.primary),
            Triple(
                "onPrimaryContainer/primaryContainer",
                scheme.onPrimaryContainer,
                scheme.primaryContainer,
            ),
            Triple("onSecondary/secondary", scheme.onSecondary, scheme.secondary),
            Triple(
                "onSecondaryContainer/secondaryContainer",
                scheme.onSecondaryContainer,
                scheme.secondaryContainer,
            ),
            Triple("onTertiary/tertiary", scheme.onTertiary, scheme.tertiary),
            Triple(
                "onTertiaryContainer/tertiaryContainer",
                scheme.onTertiaryContainer,
                scheme.tertiaryContainer,
            ),
            Triple("onError/error", scheme.onError, scheme.error),
            Triple("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer),
            Triple("inverseOnSurface/inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface),
            // The surface containers all carry body text at some point, so onSurface has to hold
            // against the darkest and lightest of them, not just against `surface`.
            Triple("onSurface/surfaceContainerLowest", scheme.onSurface, scheme.surfaceContainerLowest),
            Triple("onSurface/surfaceContainerHighest", scheme.onSurface, scheme.surfaceContainerHighest),
            Triple("onSurfaceVariant/surfaceContainerHighest", scheme.onSurfaceVariant, scheme.surfaceContainerHighest),
            // Primary is used as a text colour for emphasis, not only as a fill.
            Triple("primary/surface", scheme.primary, scheme.surface),
            Triple("error/surface", scheme.error, scheme.surface),
        )
        pairs.forEach { (label, foreground, background) ->
            assertMeets(TEXT_MINIMUM, foreground, background, "$name.$label")
        }
    }

    private fun assertUiPairs(scheme: ColorScheme, name: String) {
        val pairs = listOf(
            Triple("outline/surface", scheme.outline, scheme.surface),
            Triple("primary/surfaceContainerHighest", scheme.primary, scheme.surfaceContainerHighest),
            Triple("tertiary/surface", scheme.tertiary, scheme.surface),
        )
        pairs.forEach { (label, foreground, background) ->
            assertMeets(NON_TEXT_MINIMUM, foreground, background, "$name.$label")
        }

        // `outlineVariant` is Material 3's decorative-divider role, and WCAG 1.4.11 exempts purely
        // decorative boundaries from the 3:1 rule — Material's own baseline value does not meet it
        // either. What it must not do is disappear, so assert a visible floor instead of a
        // component-grade one. `outline`, which does bound real controls, is held to 3:1 above.
        assertVisible(scheme.outlineVariant, scheme.surface, "$name.outlineVariant/surface")
    }

    private fun assertExtendedPairs(
        colors: MegaPodcastPlayerColors,
        scheme: ColorScheme,
        name: String,
    ) {
        assertMeets(TEXT_MINIMUM, colors.onDownloaded, colors.downloaded, "$name.onDownloaded/downloaded")
        assertMeets(TEXT_MINIMUM, colors.onUnplayed, colors.unplayed, "$name.onUnplayed/unplayed")
        assertMeets(
            TEXT_MINIMUM,
            colors.onArtworkPlaceholder,
            colors.artworkPlaceholder,
            "$name.onArtworkPlaceholder/artworkPlaceholder",
        )
        // The now-playing tint labels a row that sits on its own container wash.
        assertMeets(
            TEXT_MINIMUM,
            colors.nowPlaying,
            colors.nowPlayingContainer,
            "$name.nowPlaying/nowPlayingContainer",
        )
        assertMeets(TEXT_MINIMUM, scheme.onSurface, colors.nowPlayingContainer, "$name.onSurface/nowPlayingContainer")
        // The scrubber boundary that actually carries state is played-vs-unplayed, so that pair is
        // held to the 3:1 component rule.
        assertMeets(NON_TEXT_MINIMUM, colors.waveform, colors.waveformTrack, "$name.waveform/waveformTrack")
        // The track against the page behind it only has to be *visible*. It cannot be held to 3:1
        // as well: in the light scheme the whole range from `waveform` (#4A5C00) to `surface`
        // (#FBFBF3) is 7.16:1, and a track sitting between them would need 3 x 3 = 9:1 of headroom
        // to satisfy both. Material's own LinearProgressIndicator makes the same trade, tracking
        // `surfaceVariant` against `surface`.
        assertVisible(colors.waveformTrack, scheme.surface, "$name.waveformTrack/surface")
        assertMeets(NON_TEXT_MINIMUM, colors.downloaded, scheme.surface, "$name.downloaded/surface")
        // The YouTube red is a fixed brand colour, so it only has to survive its own container.
        assertMeets(
            NON_TEXT_MINIMUM,
            colors.youtube,
            scheme.surfaceContainerHighest,
            "$name.youtube/surfaceContainerHighest",
        )
    }

    /**
     * Asserts a colour is distinguishable from its background without demanding component-grade
     * contrast. For decorative dividers and progress tracks, invisible is the only real failure.
     */
    private fun assertVisible(foreground: Color, background: Color, label: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$label is ${"%.2f".format(ratio)}:1 " +
                "(${foreground.hex()} on ${background.hex()}), needs $VISIBLE_MINIMUM:1 to be seen",
            ratio >= VISIBLE_MINIMUM,
        )
    }

    private fun assertMeets(minimum: Double, foreground: Color, background: Color, label: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$label is ${"%.2f".format(ratio)}:1 " +
                "(${foreground.hex()} on ${background.hex()}), needs $minimum:1",
            ratio >= minimum,
        )
    }

    private companion object {
        /** WCAG 2.1 AA for body text. */
        const val TEXT_MINIMUM = 4.5

        /** WCAG 2.1 AA for user-interface components and graphical objects. */
        const val NON_TEXT_MINIMUM = 3.0

        /** Floor for decorative dividers and progress tracks: present, but not shouting. */
        const val VISIBLE_MINIMUM = 1.5
    }
}

/** WCAG relative luminance of an opaque sRGB colour. */
private fun Color.relativeLuminance(): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/** WCAG contrast ratio between two opaque colours, in `1.0..21.0`. */
private fun contrastRatio(a: Color, b: Color): Double {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private fun Color.hex(): String = "#%02X%02X%02X".format(
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

/**
 * Whether a colour sits in the violet part of the wheel with enough saturation to be Material's
 * unset-role purple rather than one of the palette's near-neutral greys.
 */
private fun Color.isPurple(): Boolean {
    val maxChannel = maxOf(red, green, blue)
    val minChannel = minOf(red, green, blue)
    val chroma = maxChannel - minChannel
    if (chroma < 0.12f) return false
    // Blue dominant and red above green is the signature of the 260-290 degree baseline purples.
    return blue == maxChannel && red > green
}

/** Every role that a partially-specified scheme would silently inherit. */
private fun ColorScheme.namedRoles(): List<Pair<String, Color>> = listOf(
    "primary" to primary,
    "onPrimary" to onPrimary,
    "primaryContainer" to primaryContainer,
    "onPrimaryContainer" to onPrimaryContainer,
    "inversePrimary" to inversePrimary,
    "secondary" to secondary,
    "onSecondary" to onSecondary,
    "secondaryContainer" to secondaryContainer,
    "onSecondaryContainer" to onSecondaryContainer,
    "tertiary" to tertiary,
    "onTertiary" to onTertiary,
    "tertiaryContainer" to tertiaryContainer,
    "onTertiaryContainer" to onTertiaryContainer,
    "background" to background,
    "onBackground" to onBackground,
    "surface" to surface,
    "onSurface" to onSurface,
    "surfaceVariant" to surfaceVariant,
    "onSurfaceVariant" to onSurfaceVariant,
    "surfaceDim" to surfaceDim,
    "surfaceBright" to surfaceBright,
    "surfaceContainerLowest" to surfaceContainerLowest,
    "surfaceContainerLow" to surfaceContainerLow,
    "surfaceContainer" to surfaceContainer,
    "surfaceContainerHigh" to surfaceContainerHigh,
    "surfaceContainerHighest" to surfaceContainerHighest,
    "outline" to outline,
    "outlineVariant" to outlineVariant,
    "inverseSurface" to inverseSurface,
    "inverseOnSurface" to inverseOnSurface,
)

package md.borisveriga.megapodcastplayer.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the type scale.
 *
 * The failure this exists to catch is silent: a `TextStyle` that forgets `fontFamily` still
 * renders, just in the platform default. Nobody notices on the one screen they were looking at,
 * and the app ends up half interFamily and half Roboto. Asserting the family on every role costs
 * nothing and makes that impossible.
 */
class TypographyTest {

    @Test
    fun `display and headline roles use the display face`() {
        displayRoles().forEach { (name, style) ->
            assertEquals(
                "$name should be set in Bricolage Grotesque",
                bricolageFamily,
                style.fontFamily,
            )
        }
    }

    @Test
    fun `title body and label roles use the text face`() {
        textRoles().forEach { (name, style) ->
            assertEquals("$name should be set in interFamily", interFamily, style.fontFamily)
        }
    }

    @Test
    fun `no role falls back to the platform default font`() {
        allRoles().forEach { (name, style) ->
            val family = style.fontFamily
            assertNotNull("$name has no fontFamily and would render in the platform default", family)
            assertTrue(
                "$name resolves to $family rather than a bundled family",
                family != FontFamily.Default,
            )
        }
    }

    @Test
    fun `every role declares a size and a line height`() {
        allRoles().forEach { (name, style) ->
            assertTrue("$name has no fontSize", !style.fontSize.isUnspecified)
            assertTrue("$name has no lineHeight", !style.lineHeight.isUnspecified)
            assertTrue(
                "$name has lineHeight ${style.lineHeight} below its fontSize ${style.fontSize}",
                style.lineHeight.value >= style.fontSize.value,
            )
        }
    }

    /**
     * The numeric styles must carry tabular figures.
     *
     * This is the whole reason they exist. Without `tnum`, a running timecode re-measures every
     * second and everything laid out beside it jitters — the single most visible small defect a
     * player UI can have.
     */
    @Test
    fun `numeric styles use tabular figures`() {
        listOf(
            "numeric" to megaPodcastPlayerTypeExtras.numeric,
            "numericLarge" to megaPodcastPlayerTypeExtras.numericLarge,
        ).forEach { (name, style) ->
            assertEquals("$name must request tabular figures", TABULAR_FIGURES, style.fontFeatureSettings)
            assertEquals("$name should be set in interFamily", interFamily, style.fontFamily)
        }
    }

    private fun displayRoles(): List<Pair<String, TextStyle>> = with(megaPodcastPlayerTypography) {
        listOf(
            "displayLarge" to displayLarge,
            "displayMedium" to displayMedium,
            "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge,
            "headlineMedium" to headlineMedium,
            "headlineSmall" to headlineSmall,
        )
    }

    private fun textRoles(): List<Pair<String, TextStyle>> = with(megaPodcastPlayerTypography) {
        listOf(
            "titleLarge" to titleLarge,
            "titleMedium" to titleMedium,
            "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge,
            "bodyMedium" to bodyMedium,
            "bodySmall" to bodySmall,
            "labelLarge" to labelLarge,
            "labelMedium" to labelMedium,
            "labelSmall" to labelSmall,
        )
    }

    private fun allRoles(): List<Pair<String, TextStyle>> = displayRoles() + textRoles()
}

/** True when the [Typography] default was left in place; used only for the readable assertion above. */
private val androidx.compose.ui.unit.TextUnit.isUnspecified: Boolean
    get() = this == androidx.compose.ui.unit.TextUnit.Unspecified

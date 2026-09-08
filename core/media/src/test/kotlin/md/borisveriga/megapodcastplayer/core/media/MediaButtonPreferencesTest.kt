package md.borisveriga.megapodcastplayer.core.media

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests the button set the lock screen and the shade draw.
 *
 * The failure this guards against is the one that shipped: a notification whose only controls were
 * previous and play, with no way to skip an ad or replay a sentence. It is invisible from inside
 * the app, because the app's own player has all five buttons — so what is asserted here is the
 * slots, the commands behind them, and the rule that a glyph never shows a number the player will
 * not honour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaButtonPreferencesTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `puts skip back and skip forward in the compact slots`() {
        val buttons = mediaButtonPreferences(application, PlaybackSettings())

        val back = buttons.single { it.playerCommand == Player.COMMAND_SEEK_BACK }
        val forward = buttons.single { it.playerCommand == Player.COMMAND_SEEK_FORWARD }

        assertEquals(CommandButton.SLOT_BACK, back.slots[0])
        assertEquals(CommandButton.SLOT_FORWARD, forward.slots[0])
    }

    @Test
    fun `puts the episode controls in the secondary slots, where only the expanded view shows them`() {
        val buttons = mediaButtonPreferences(application, PlaybackSettings())

        val previous = buttons.single { it.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS }
        val next = buttons.single { it.playerCommand == Player.COMMAND_SEEK_TO_NEXT }

        assertEquals(CommandButton.SLOT_BACK_SECONDARY, previous.slots[0])
        assertEquals(CommandButton.SLOT_FORWARD_SECONDARY, next.slots[0])
    }

    @Test
    fun `every button can fall back to the overflow`() {
        val buttons = mediaButtonPreferences(application, PlaybackSettings())

        // Surfaces with fewer slots than this list has buttons — Android Auto, a compact Now Bar —
        // drop a button that names no slot they have.
        buttons.forEach { button ->
            assertTrue(
                "${button.displayName} has no overflow fallback",
                button.slots.contains(CommandButton.SLOT_OVERFLOW),
            )
        }
    }

    @Test
    fun `every button is labelled`() {
        val buttons = mediaButtonPreferences(application, PlaybackSettings())

        buttons.forEach { button ->
            assertTrue("a button has no display name", button.displayName.isNotBlank())
        }
    }

    @Test
    fun `the skip labels say the configured number of seconds`() {
        val buttons = mediaButtonPreferences(
            application,
            PlaybackSettings(skipBackMs = 15_000L, skipForwardMs = 45_000L),
        )

        val back = buttons.single { it.playerCommand == Player.COMMAND_SEEK_BACK }
        val forward = buttons.single { it.playerCommand == Player.COMMAND_SEEK_FORWARD }

        assertTrue(back.displayName.contains("15"))
        assertTrue(forward.displayName.contains("45"))
    }

    @Test
    fun `numbered glyphs are used only where Media3 has one`() {
        assertEquals(CommandButton.ICON_SKIP_BACK_5, skipBackIconConstant(5_000L))
        assertEquals(CommandButton.ICON_SKIP_BACK_10, skipBackIconConstant(10_000L))
        assertEquals(CommandButton.ICON_SKIP_BACK_15, skipBackIconConstant(15_000L))
        assertEquals(CommandButton.ICON_SKIP_BACK_30, skipBackIconConstant(30_000L))

        assertEquals(CommandButton.ICON_SKIP_FORWARD_5, skipForwardIconConstant(5_000L))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_10, skipForwardIconConstant(10_000L))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_15, skipForwardIconConstant(15_000L))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_30, skipForwardIconConstant(30_000L))
    }

    @Test
    fun `an interval with no numbered glyph falls back to the plain one`() {
        // 45 and 60 seconds are both offered in Settings and neither has a numbered icon; a button
        // that said 30 and jumped 45 would be a lie the user catches on first use.
        assertEquals(CommandButton.ICON_SKIP_BACK, skipBackIconConstant(45_000L))
        assertEquals(CommandButton.ICON_SKIP_FORWARD, skipForwardIconConstant(60_000L))
    }
}

package md.borisveriga.megapodcastplayer.feature.player.video

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Tests for [videoShowsOverlay]: which windows get the video screen's page and which its overlay.
 *
 * The windows are the ones this app meets. What used to decide was which way the window was
 * turned, and the row that shows why that was wrong is the Fold opened out: wider than it is tall,
 * and with more room for a page than any phone.
 *
 * Heights are plain numbers of dp: the runner builds each case by reflection, and a value class
 * in the constructor is not a parameter it can fill.
 */
@RunWith(Parameterized::class)
class VideoShapeTest(
    private val window: String,
    private val heightDp: Int,
    private val fullscreen: Boolean,
    private val overlay: Boolean,
) {

    @Test
    fun `the window gets the shape it has the height for`() {
        assertEquals(window, overlay, videoShowsOverlay(windowHeight = heightDp.dp, fullscreen = fullscreen))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun windows(): List<Array<Any>> = listOf(
            arrayOf("a phone upright", 891, false, false),
            arrayOf("a phone on its side", 411, false, true),
            arrayOf("the Fold closed, upright", 884, false, false),
            arrayOf("the Fold closed, on its side", 373, false, true),
            arrayOf("the Fold open", 830, false, false),
            arrayOf("the Fold open, turned", 882, false, false),
            arrayOf("a tablet on its side", 800, false, false),
            arrayOf("a phone's half of a split screen", 420, false, true),
            arrayOf("just under the medium height", 479, false, true),
            arrayOf("the medium height itself", 480, false, false),
            arrayOf("a phone upright, full screen asked for", 891, true, true),
            arrayOf("the Fold open, full screen asked for", 830, true, true),
        )
    }
}

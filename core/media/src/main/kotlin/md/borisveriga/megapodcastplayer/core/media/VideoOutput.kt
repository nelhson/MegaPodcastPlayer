package md.borisveriga.megapodcastplayer.core.media

import android.view.SurfaceView
import android.view.TextureView

/**
 * Somewhere the player can draw the picture of an episode being watched.
 *
 * There are two such places and the player draws on one at a time: giving it a second takes the
 * picture from the first, and Media3 does not give it back when the second goes. Naming the
 * destination as a value is what lets one owner decide which of the two is current and hand the
 * picture to the survivor; see [PlaybackConnection.showVideoOn].
 */
sealed interface VideoOutput {

    /**
     * The video screen's own surface.
     *
     * @property view the surface to draw on.
     */
    data class Screen(val view: SurfaceView) : VideoOutput

    /**
     * The collapsed bar's texture, for a picture that is a piece of a layout.
     *
     * @property view the texture to draw on.
     */
    data class Bar(val view: TextureView) : VideoOutput
}

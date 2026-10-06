package md.borisveriga.megapodcastplayer.feature.player.video

import md.borisveriga.megapodcastplayer.core.media.PlaybackError
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [videoControlsAutoHide]: the video screen's controls hide on their own only while they
 * are up over a picture that is moving, with nothing open from them and nothing to say.
 */
class VideoControlsAutoHideTest {

    private val playing = PlaybackState(isConnected = true, episodeId = "e1", isPlaying = true)

    private fun autoHide(
        visible: Boolean = true,
        playback: PlaybackState = playing,
        refused: Boolean = false,
        held: Boolean = false,
    ) = videoControlsAutoHide(visible = visible, playback = playback, refused = refused, held = held)

    @Test
    fun `controls over a playing picture hide on their own`() {
        assertTrue(autoHide())
    }

    @Test
    fun `controls already hidden have nothing to hide`() {
        assertFalse(autoHide(visible = false))
    }

    @Test
    fun `a paused picture keeps its controls`() {
        assertFalse(autoHide(playback = playing.copy(isPlaying = false)))
    }

    @Test
    fun `a buffering or retrying picture keeps its controls`() {
        assertFalse(autoHide(playback = playing.copy(isBuffering = true)))
    }

    @Test
    fun `a failed picture keeps its controls`() {
        assertFalse(autoHide(playback = playing.copy(error = PlaybackError.NO_CONNECTION)))
    }

    @Test
    fun `a refused picture keeps its controls`() {
        assertFalse(autoHide(refused = true))
    }

    @Test
    fun `a sheet opened from the controls keeps them`() {
        assertFalse(autoHide(held = true))
    }
}

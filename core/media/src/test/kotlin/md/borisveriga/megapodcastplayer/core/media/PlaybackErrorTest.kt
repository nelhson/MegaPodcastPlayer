package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the playback-failure classifier.
 *
 * The mapping is the whole of PL-8: what the user is told when an episode will not play. It is
 * pinned by error *code* rather than by message on purpose, and this is where that decision is
 * enforced — a classifier that started reading `exception.message` would keep passing until the
 * next Media3 release reworded something.
 */
class PlaybackErrorTest {

    private fun exception(code: Int) = PlaybackException(
        /* message = */ "Source error",
        /* cause = */ null,
        /* errorCode = */ code,
    )

    @Test
    fun `nothing failed, nothing to say`() {
        assertNull(playbackErrorOf(null, isYouTube = false))
        assertNull(playbackErrorOf(null, isYouTube = true))
    }

    @Test
    fun `a network that could not be reached reads as a connection problem`() {
        assertEquals(
            PlaybackError.NO_CONNECTION,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
                isYouTube = false,
            ),
        )
        assertEquals(
            PlaybackError.NO_CONNECTION,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
                isYouTube = false,
            ),
        )
    }

    @Test
    fun `a host that answered and said no reads as the episode having moved`() {
        // The commonest real failure on a feed that has been around a few years: the audio was
        // re-hosted and the enclosure URL in the stored episode is stale.
        assertEquals(
            PlaybackError.EPISODE_GONE,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
                isYouTube = false,
            ),
        )
        assertEquals(
            PlaybackError.EPISODE_GONE,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
                isYouTube = false,
            ),
        )
    }

    @Test
    fun `bytes this device cannot decode read as a format problem`() {
        assertEquals(
            PlaybackError.UNSUPPORTED_FORMAT,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
                isYouTube = false,
            ),
        )
        assertEquals(
            PlaybackError.UNSUPPORTED_FORMAT,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
                isYouTube = false,
            ),
        )
    }

    @Test
    fun `a YouTube episode fails as a YouTube episode, whatever the code says`() {
        // Its audio URL is resolved at play time and expires, so a stale one comes back as a bad
        // HTTP status like any other dead link — but there is nothing wrong with the connection and
        // nothing wrong with the show, and trying again is what actually works.
        assertEquals(
            PlaybackError.YOUTUBE_UNAVAILABLE,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
                isYouTube = true,
            ),
        )
        assertEquals(
            PlaybackError.YOUTUBE_UNAVAILABLE,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
                isYouTube = true,
            ),
        )
    }

    @Test
    fun `anything else keeps the player's own words`() {
        assertEquals(
            PlaybackError.UNKNOWN,
            playbackErrorOf(
                exception(PlaybackException.ERROR_CODE_UNSPECIFIED),
                isYouTube = false,
            ),
        )
    }
}

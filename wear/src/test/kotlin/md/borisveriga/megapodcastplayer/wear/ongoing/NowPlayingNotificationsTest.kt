package md.borisveriga.megapodcastplayer.wear.ongoing

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [shouldShowChip], the one decision behind the watch-face chip.
 *
 * The rest of [NowPlayingNotifications] is plumbing onto the notification manager. This is the part
 * that decides whether the watch face makes a claim about what the phone is doing, and every way of
 * getting it wrong shows the user something untrue.
 */
class NowPlayingNotificationsTest {

    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "Episode one",
        showTitle = "The Show",
        isPlaying = true,
        positionMs = 30_000L,
        durationMs = 300_000L,
    )

    @Test
    fun `a playing phone gets a chip`() {
        assertTrue(shouldShowChip(playing))
    }

    @Test
    fun `a paused phone does not`() {
        // A chip that survives a pause is indistinguishable from one that is simply stale.
        assertFalse(shouldShowChip(playing.copy(isPlaying = false)))
    }

    @Test
    fun `an idle phone does not, whatever its playing flag says`() {
        assertFalse(shouldShowChip(NowPlayingSnapshot(isPlaying = true)))
    }

    @Test
    fun `an unreadable or absent snapshot does not`() {
        // Which covers both a phone that has said nothing and one running a build this watch cannot
        // parse: in neither case is there anything true to put on the watch face.
        assertFalse(shouldShowChip(null))
    }
}

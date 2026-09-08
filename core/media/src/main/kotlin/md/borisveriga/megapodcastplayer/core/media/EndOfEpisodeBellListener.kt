package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Rings [EpisodeEndBell] when the episode playing reaches its end.
 *
 * Installed on the [androidx.media3.exoplayer.ExoPlayer] that [PlaybackService] owns, beside
 * [PlaybackPersistenceListener], and split out of it for the same reason: the rule about which
 * discontinuity counts as "finished" is a judgement call worth testing against a stubbed [Player]
 * rather than a running service.
 *
 * It watches the same two signals the persistence listener does, because between them they are the
 * only two ways an episode ends on its own:
 *
 *  - an automatic transition, which is an episode mid-queue finishing and the next one starting;
 *  - [Player.STATE_ENDED], which is the last episode in the queue finishing.
 *
 * A seek and a user tapping "next" are neither, and deliberately ring nothing: the point of the bell
 * is to catch the moment nobody was awake for.
 *
 * @property player the player being observed; also what gets paused when the bell rings.
 * @property scope where the ring runs; the service cancels it when the player is released.
 * @property bell the arming, consumed on the way past so the bell fires once.
 * @property ringer where the ring actually goes.
 */
internal class EndOfEpisodeBellListener(
    private val player: Player,
    private val scope: CoroutineScope,
    private val bell: EpisodeEndBell,
    private val ringer: BellRinger,
) : Player.Listener {

    // Same opt-in, and the same reason, as PlaybackPersistenceListener: `PositionInfo.mediaItem` is
    // still unstable, and it is the only way to learn which item was playing *before* an automatic
    // transition. Scoped to the one callback that needs it.
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
        ringFor(oldPosition.mediaItem)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        ringFor(player.currentMediaItem)
    }

    /**
     * Rings the bell for a finished episode, if it was armed.
     *
     * The pause is the half that matters most and has to happen here, synchronously on the player's
     * thread: after an automatic transition the *next* episode is already playing, and a user who
     * asked to be woken at the end of one episode has not asked to be read the rest of their queue.
     * After [Player.STATE_ENDED] there is nothing left to pause and the call is a harmless no-op,
     * which is why it is not conditional.
     *
     * @param finished the item that ended, for its title; null is not an error.
     */
    private fun ringFor(finished: MediaItem?) {
        if (!bell.consume()) return
        player.pause()
        val episodeTitle = finished?.mediaMetadata?.title?.toString()
        scope.launch { ringer.ring(episodeTitle) }
    }
}

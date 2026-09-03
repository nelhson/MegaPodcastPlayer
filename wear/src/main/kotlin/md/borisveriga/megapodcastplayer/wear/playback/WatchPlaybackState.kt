package md.borisveriga.megapodcastplayer.wear.playback

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.wear.data.StoredEpisode

/**
 * What the watch's own player is doing.
 *
 * Only present when the watch is playing its own copy of an episode. Everything else on this screen
 * describes the phone; this is the one thing that describes the watch.
 *
 * @property episode the episode loaded, which the watch holds on disk.
 * @property isPlaying true while audio is actually coming out of the watch.
 * @property isBuffering true while the player is preparing; brief, since the file is local, but not
 *   instant on a watch's storage.
 * @property positionMs where playback has reached.
 * @property durationMs what the player measured, which is more trustworthy than the feed's figure —
 *   it is reading the actual file. Zero until it has read enough to know.
 * @property speed the playback rate.
 */
data class WatchPlaybackState(
    val episode: StoredEpisode,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
) {

    /**
     * The same state, in the shape the screen already knows how to draw.
     *
     * The watch screen renders a [NowPlayingSnapshot], and local playback is the same four facts
     * about a different device — so it presents itself as one rather than growing a second rendering
     * path beside the first. What the phone would fill in and the watch cannot is left at its
     * default: there is no queue on the watch, so nothing is next or previous.
     *
     * @param skipForwardMs the interval the phone last reported, so both devices' buttons agree.
     * @param skipBackMs likewise.
     */
    fun asSnapshot(skipForwardMs: Long, skipBackMs: Long): NowPlayingSnapshot = NowPlayingSnapshot(
        episodeId = episode.id,
        title = episode.title,
        showTitle = episode.showTitle,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        positionMs = positionMs,
        // The player's own measurement when it has one, the phone's otherwise: a feed that lied
        // about the length should not stop the bar working once the file itself has been read.
        durationMs = if (durationMs > 0L) durationMs else episode.durationMs,
        speed = speed,
        skipForwardMs = skipForwardMs,
        skipBackMs = skipBackMs,
    )
}

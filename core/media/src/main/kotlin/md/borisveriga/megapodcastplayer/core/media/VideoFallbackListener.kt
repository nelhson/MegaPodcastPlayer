package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter

/**
 * Hands an episode back to sound when its picture fails to load.
 *
 * The video flavour is one merged source, so a picture that cannot be fetched — the video has no
 * playable rendition, or its URL keeps being refused — stops the sound with it. Sound is what the
 * user came for, so on an error while the current item is in video flavour this swaps it back and
 * lets playback carry on — once [PlaybackRetryListener] has given up asking for it again, which is
 * why the error half is [fallBack] rather than this listener's own `onPlayerError`. The video
 * screen sees the drop to sound and says so; the collapsed bar, where the picture may be instead,
 * goes back to the episode's artwork and says nothing.
 *
 * The other way a picture goes missing makes no error at all. A rendition in a codec this phone
 * has no decoder for is simply not selected: the sound plays, the item still says it is video, and
 * the screen waits for a frame that will never come. That is handed back to sound as well, so the
 * screen can say what happened.
 *
 * An error on the sound itself is left alone: the item is then already in audio flavour, which is
 * also what stops a failing sound from bouncing back and forth.
 *
 * @property player the player whose errors are watched and whose playlist is changed.
 * @property crashReporter where the failed picture is recorded; the screen shows a sentence, the
 *   report keeps the cause.
 */
internal class VideoFallbackListener(
    private val player: Player,
    private val crashReporter: CrashReporter,
) : Player.Listener {

    /**
     * Swaps a failed video item back to sound.
     *
     * Called by [PlaybackRetryListener] when its retries are used up, not on every error: a
     * picture refused once is usually shown on the next ask.
     *
     * @param error what the player failed with.
     */
    fun fallBack(error: PlaybackException) {
        if (player.currentMediaItem?.isVideoFlavour != true) return
        crashReporter.recordNonFatal(NON_FATAL_VIDEO_FALLBACK, error)
        // Also prepares again, since the error left the player idle.
        player.exitVideoMode()
    }

    /**
     * Swaps a video item whose picture the player found but cannot decode back to sound.
     *
     * Only when the tracks say so outright: a video track is listed and none of them is playable
     * here. An empty list is the moment before the source has been read, not a verdict. A track
     * the decoder reports as beyond what it advertises still counts as playable, because the track
     * selector plays it anyway — a 60 fps rendition on a decoder that claims 30 is usually fine,
     * and sending it back to sound would refuse a picture this phone could have shown.
     *
     * @param tracks what the current item turned out to contain.
     */
    override fun onTracksChanged(tracks: Tracks) {
        if (player.currentMediaItem?.isVideoFlavour != true) return
        val playable = tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, /* allowExceedsCapabilities = */ true)
        if (!tracks.containsType(C.TRACK_TYPE_VIDEO) || playable) return
        crashReporter.recordNonFatal(
            NON_FATAL_VIDEO_UNDECODABLE,
            IllegalStateException("no decoder for the video track on offer"),
        )
        player.exitVideoMode()
    }

    private companion object {
        /** One message for the failure kind, fixed, so each groups into one report. */
        const val NON_FATAL_VIDEO_FALLBACK = "Video failed; fell back to audio"

        /** Likewise, for a picture that loaded and could not be decoded. */
        const val NON_FATAL_VIDEO_UNDECODABLE = "Video not decodable; fell back to audio"
    }
}

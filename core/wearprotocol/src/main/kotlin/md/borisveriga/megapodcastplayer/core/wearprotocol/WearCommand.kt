package md.borisveriga.megapodcastplayer.core.wearprotocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something the watch asks the phone to do.
 *
 * The watch owns no player, so every button on it becomes one of these, travels over the Data Layer
 * and is applied to the phone's ExoPlayer. Nothing here carries player *state*: the watch does not
 * decide what happens next, it only asks, and learns the result from the next
 * [NowPlayingSnapshot].
 *
 * Each variant carries an explicit [SerialName] so that a command read straight out of
 * `adb shell dumpsys` names itself, whatever the Kotlin class is called.
 */
@Serializable
sealed interface WearCommand {

    /** Starts playback if paused, pauses it if playing. */
    @Serializable
    @SerialName("toggle_play_pause")
    data object TogglePlayPause : WearCommand

    /**
     * Jumps forward by the interval the *user* configured on the phone.
     *
     * The amount deliberately does not travel with the command: the phone owns that preference, and
     * a watch that sent its own number could disagree with the phone's own skip button.
     */
    @Serializable
    @SerialName("skip_forward")
    data object SkipForward : WearCommand

    /** Jumps back by the interval configured on the phone; see [SkipForward]. */
    @Serializable
    @SerialName("skip_back")
    data object SkipBack : WearCommand

    /** Moves to the next queued episode, if there is one. */
    @Serializable
    @SerialName("skip_to_next")
    data object SkipToNext : WearCommand

    /** Restarts the episode, or moves to the previous one if already near the start. */
    @Serializable
    @SerialName("skip_to_previous")
    data object SkipToPrevious : WearCommand

    /** Advances to the next speed in [md.borisveriga.megapodcastplayer.core.model.PlaybackSettings.SPEED_STEPS]. */
    @Serializable
    @SerialName("cycle_speed")
    data object CycleSpeed : WearCommand

    /**
     * Asks the phone to publish its state again.
     *
     * Sent when the watch app opens. The watch already has the last published snapshot from the
     * Data Layer's cache, but it may be minutes old — or the phone process may have been killed
     * since — and this both refreshes it and starts the phone's process if it is not running.
     */
    @Serializable
    @SerialName("request_state")
    data object RequestState : WearCommand

    /**
     * Seeks within the current episode.
     *
     * @property positionMs the absolute position to seek to; clamped by the phone.
     */
    @Serializable
    @SerialName("seek_to")
    data class SeekTo(val positionMs: Long) : WearCommand

    /**
     * Plays a queued episode from the top of the watch's "up next" list.
     *
     * @property episodeId the episode to play, as sent in [QueuedEpisode.id].
     */
    @Serializable
    @SerialName("play_episode")
    data class PlayEpisode(val episodeId: String) : WearCommand

    /**
     * Asks the phone to copy a downloaded episode's audio onto the watch.
     *
     * The phone answers on a channel rather than in a reply — see [WearPaths.EPISODE_AUDIO] — so
     * nothing here says how it went. The watch learns that from bytes arriving, or from their
     * absence.
     *
     * @property episodeId the episode, as sent in [OfflineEpisode.id].
     */
    @Serializable
    @SerialName("copy_to_watch")
    data class CopyToWatch(val episodeId: String) : WearCommand

    /**
     * Asks the phone to stop a copy that is under way.
     *
     * The watch stops reading on its own the moment the wearer taps cancel — it does not need
     * permission to throw away bytes it is receiving. This exists for the other half: without it the
     * phone would keep pouring an episode nobody is catching down the Bluetooth link, holding a
     * foreground service up for the minutes that takes.
     *
     * Naming an episode rather than meaning "stop everything" because the phone may be sending more
     * than one, and cancelling the row the wearer tapped must not cancel the row they did not.
     *
     * @property episodeId the episode whose transfer to abandon.
     */
    @Serializable
    @SerialName("cancel_copy_to_watch")
    data class CancelCopyToWatch(val episodeId: String) : WearCommand

    /**
     * Tells the phone where an episode played *on the watch* got to.
     *
     * The one command that carries state in the other direction, and it has to: audio the watch
     * played is audio the phone did not, so without this an episode listened to on a run would
     * still be waiting at zero on the phone afterwards.
     *
     * @property episodeId the episode that was played.
     * @property positionMs where it was left.
     * @property isPlayed whether it reached the end.
     */
    @Serializable
    @SerialName("report_position")
    data class ReportPosition(
        val episodeId: String,
        val positionMs: Long,
        val isPlayed: Boolean = false,
    ) : WearCommand
}

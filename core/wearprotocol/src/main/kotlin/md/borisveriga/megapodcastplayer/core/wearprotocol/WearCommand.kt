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
     * Sets the phone's media volume.
     *
     * Absolute rather than "one step quieter", for three reasons. It is idempotent, so a wrist
     * turning fast may drop every intermediate value and send only the last one — which matters on
     * a path the Data Layer never de-duplicates and never reorders for you. The scale it is
     * expressed in is the phone's own, sent in [NowPlayingSnapshot.maxVolume], so the watch is
     * spared inventing one; that is the same doctrine as [SkipForward], where the amount stays on
     * the phone. And an absolute value cannot accumulate error: a lost relative step would leave
     * the two devices disagreeing about the level forever.
     *
     * @property level the level to set, on the phone's `0..maxVolume` scale; clamped by the phone.
     */
    @Serializable
    @SerialName("set_volume")
    data class SetVolume(val level: Int) : WearCommand

    /**
     * Plays a queued episode from the top of the watch's "up next" list.
     *
     * @property episodeId the episode to play, as sent in [WatchEpisode.id].
     */
    @Serializable
    @SerialName("play_episode")
    data class PlayEpisode(val episodeId: String) : WearCommand

    /**
     * Puts an episode at the end of the phone's queue, without interrupting what is playing.
     *
     * The other half of what the wrist is for: [PlayEpisode] is "this one instead", and this is
     * "this one after". Sent from the downloaded list, where starting an episode over the one in
     * your ears is rarely what was meant.
     *
     * Enqueuing the same episode twice is harmless — the phone's queue holds an episode once — which
     * is what makes it safe as a message the Data Layer will never de-duplicate.
     *
     * @property episodeId the episode to queue, as sent in [WatchEpisode.id].
     */
    @Serializable
    @SerialName("queue_episode")
    data class QueueEpisode(val episodeId: String) : WearCommand

    /**
     * Marks the moment the wearer just heard.
     *
     * Carries no position on purpose. The watch knows nothing about where the phone really is —
     * the position it last saw is a snapshot several seconds old, extrapolated by a clock that is
     * not the phone's — so it sends this empty and the phone marks its own playhead, which is the
     * only position that is true.
     *
     * A duplicate is harmless by construction — see `MomentsRepository.mark`, which folds a second
     * mark within a few seconds into the first — which is what makes it safe to send this as a
     * message that the Data Layer will never de-duplicate.
     */
    @Serializable
    @SerialName("mark_moment")
    data object MarkMoment : WearCommand
}

package md.borisveriga.megapodcastplayer.core.media

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope

/**
 * How much longer the player will keep going, if the user has said.
 *
 * @property remainingMs milliseconds left on a counted-down timer, or null when none is running.
 * @property isEndOfEpisode whether the player will stop when the episode finishes — the bell's
 *   behaviour, which is now one of the timer's options rather than a second control beside it.
 */
data class SleepTimerState(
    val remainingMs: Long? = null,
    val isEndOfEpisode: Boolean = false,
) {
    /** True when the player is going to stop on its own. */
    val isArmed: Boolean get() = remainingMs != null || isEndOfEpisode
}

/**
 * Stops playback after a while, for someone falling asleep.
 *
 * The app had half of this already, as [EpisodeEndBell]: a one-shot that rang at the end of the
 * episode playing. That is a good answer to exactly one version of the question — "wake me when
 * this finishes" — and no answer at all to the one people ask more often, which is "stop in twenty
 * minutes". Both live here now, behind one control, because two "stop later" buttons on a transport
 * row is one too many and because a user choosing between them is choosing between two shapes of
 * the same intention.
 *
 * **The fade is the point.** Playback does not cut off; it falls away over
 * [FADE_DURATION_MS] and then pauses. Audio that stops mid-sentence at full volume is exactly the
 * thing that wakes a person who was nearly asleep, which is the opposite of what they asked for.
 * The volume is put back afterwards, so the next play starts at full — a player left silent by a
 * timer is a player that appears broken.
 *
 * **Wall clock, not playback time.** The countdown runs whether or not audio is playing, because
 * "stop in twenty minutes" is a statement about the room, not about the episode.
 *
 * In memory rather than persisted, for the same reasons [EpisodeEndBell] is: it is a one-shot for
 * tonight, and a process that died is not one still playing into a sleeping user's ear.
 *
 * @property connection the player: what gets faded and paused.
 * @property bell the end-of-episode arming, which this now owns the UI story for.
 * @property scope outlives every screen, because the timer has to keep counting with the app in the
 *   background — which is where it will be, on a phone lying face down.
 */
@Singleton
class SleepTimer @Inject constructor(
    private val connection: PlaybackConnection,
    private val bell: EpisodeEndBell,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val timerState = MutableStateFlow(SleepTimerState())

    /**
     * The countdown, or null.
     *
     * Held so a new arming replaces the old one rather than running two timers at once, which is
     * what "15 minutes" tapped twice would otherwise mean.
     */
    private var countdown: Job? = null

    /** What the player will do, and when; the player's own button renders from this. */
    val state: StateFlow<SleepTimerState> = timerState.asStateFlow()

    /**
     * Stops playback after a fixed time.
     *
     * @param durationMs how long from now. A non-positive value cancels instead of firing at once,
     *   which is what a badly computed "end of chapter" on the last second of a chapter would
     *   otherwise do.
     */
    fun armAfter(durationMs: Long) {
        cancel()
        if (durationMs <= 0L) return

        timerState.value = SleepTimerState(remainingMs = durationMs)
        countdown = scope.launch {
            var remaining = durationMs
            while (isActive && remaining > 0L) {
                val step = minOf(TICK_MS, remaining)
                delay(step)
                remaining -= step
                timerState.value = SleepTimerState(remainingMs = remaining)
            }
            fadeOutAndPause()
            timerState.value = SleepTimerState()
        }
    }

    /**
     * Stops playback when the episode finishes, and rings.
     *
     * Delegates to [EpisodeEndBell], which is where the rule lives about which discontinuity counts
     * as an episode ending — a judgement the player's own listener makes on the player's thread and
     * that nothing here could make as well.
     */
    fun armEndOfEpisode() {
        cancel()
        bell.arm()
        timerState.value = SleepTimerState(isEndOfEpisode = true)
    }

    /**
     * Adds time to a running countdown.
     *
     * What a shake means: "I am still awake". Does nothing when no countdown is running, including
     * for the end-of-episode option — there is no number there to add to, and reinterpreting a
     * shake as "switch to a timer" would be the app deciding something the user did not say.
     *
     * @param byMs how much to add.
     */
    fun extend(byMs: Long) {
        val remaining = timerState.value.remainingMs ?: return
        armAfter(remaining + byMs)
    }

    /** Calls the whole thing off; the player keeps going. */
    fun cancel() {
        countdown?.cancel()
        countdown = null
        bell.disarm()
        timerState.value = SleepTimerState()
    }

    /**
     * Fades the player out and pauses it.
     *
     * The volume is restored *after* the pause rather than left where the fade ended, so that the
     * next thing the user plays — tomorrow morning, having forgotten all of this — starts at full
     * volume.
     */
    private suspend fun fadeOutAndPause() {
        repeat(FADE_STEPS) { step ->
            connection.setVolume(1f - (step + 1).toFloat() / FADE_STEPS)
            delay(FADE_DURATION_MS / FADE_STEPS)
        }
        connection.pause()
        connection.setVolume(1f)
    }

    private companion object {
        /** How often the countdown publishes; a sleep timer is read in minutes, ticked in seconds. */
        const val TICK_MS = 1_000L

        /** Long enough to be a fade rather than a cut, short enough not to be a fifth of a chapter. */
        const val FADE_DURATION_MS = 10_000L

        /** Steps in the fade; enough that it is not audibly stepped on any device. */
        const val FADE_STEPS = 40
    }
}

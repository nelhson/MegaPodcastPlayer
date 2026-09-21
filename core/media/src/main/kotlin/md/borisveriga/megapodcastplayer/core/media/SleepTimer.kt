package md.borisveriga.megapodcastplayer.core.media

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope

/**
 * How much longer the player will keep going, if the user has said.
 *
 * @property remainingMs milliseconds left on a counted-down timer, or null when none is running.
 * @property isEndOfEpisode whether the player will stop when the episode finishes — the bell's
 *   behaviour, which is now one of the timer's options rather than a second control beside it.
 * @property endOfChapterIndex the index of the chapter the player will stop at the end of, or null.
 *   [remainingMs] stays null alongside it: the stop is a place in the episode rather than a time in
 *   the room, and how long it takes to get there depends on a speed the listener may yet change.
 */
data class SleepTimerState(
    val remainingMs: Long? = null,
    val isEndOfEpisode: Boolean = false,
    val endOfChapterIndex: Int? = null,
) {
    /** True when the player is going to stop on its own. */
    val isArmed: Boolean
        get() = remainingMs != null || isEndOfEpisode || endOfChapterIndex != null
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
 * **Wall clock for a length of time, playback position for a chapter.** The countdown runs whether
 * or not audio is playing, because "stop in twenty minutes" is a statement about the room, not
 * about the episode. "Stop at the end of chapter four" is the opposite — a statement about the
 * episode — so that option watches the playhead instead, and a pause, a seek or a change of speed
 * moves the stop with it rather than leaving a countdown to fire in the middle of a sentence.
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
     * The countdown or the chapter watch, or null.
     *
     * Held so a new arming replaces the old one rather than running two timers at once, which is
     * what "15 minutes" tapped twice would otherwise mean.
     */
    private var countdown: Job? = null

    /**
     * Whether what [state] says is the bell's doing rather than a job of this class's own.
     *
     * Volatile because it is written where the arming happens and read where the bell is watched,
     * and those are different threads.
     */
    @Volatile
    private var isBellBacked = false

    /** What the player will do, and when; the player's own button renders from this. */
    val state: StateFlow<SleepTimerState> = timerState.asStateFlow()

    init {
        // The bell is rung, and so disarmed, by the player's own listener, which knows nothing of
        // this class. Without this the button would go on saying "armed" about a bell that rang an
        // hour ago — and the sheet would tick the same chapter number of whatever played next.
        scope.launch {
            bell.armed.collect { armed ->
                if (!armed) {
                    // Asked again inside the update, which may run late or twice: an arming that
                    // got in between has armed the bell again, and its state is not this one's to
                    // clear.
                    timerState.update { current ->
                        if (isBellBacked && !bell.armed.value) SleepTimerState() else current
                    }
                }
            }
        }
    }

    /**
     * Stops playback after a fixed time.
     *
     * @param durationMs how long from now. A non-positive value cancels instead of firing at once,
     *   because pausing instantly is the most surprising possible answer to a tap on a sleep timer.
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
        isBellBacked = true
        bell.arm()
        timerState.value = SleepTimerState(isEndOfEpisode = true)
    }

    /**
     * Stops playback at the end of a chosen chapter.
     *
     * Watches the playhead rather than counting down, so the stop stays on the chapter boundary
     * whatever happens in between. The fade is timed to *end* on the boundary: it starts as far
     * short of it as [FADE_DURATION_MS] of listening covers at the speed being played, and is cut
     * shorter when the timer is armed with less of the chapter left than that. Only a playing
     * player starts it — a playhead scrubbed into the last seconds of the chapter while paused has
     * not got there by listening, and fading a silent player would spend the timer on nothing.
     *
     * Three things end the watch without pausing anything: another arming, [cancel], and the
     * playhead turning up somewhere this no longer describes — a different episode, or well past
     * the boundary. The second of those is a seek, and someone who seeks beyond the chapter they
     * asked to stop after is awake and has changed their mind; pausing on them the instant they
     * let go of the scrubber would be the timer winning an argument nobody was having. A seek
     * *back* out of a fade that has begun abandons the fade, for the same reason, and the watch
     * carries on.
     *
     * @param chapterIndex which chapter; only published, so the sheet can tick it.
     * @param episodeId the episode the chapter belongs to.
     * @param stopAtMs where the chapter ends, or null when it is the last one and runs to the end
     *   of the episode — which is then [EpisodeEndBell]'s judgement to make, as it is for
     *   [armEndOfEpisode]. Unlike that option this one is about a chapter *of this episode*, so
     *   the bell is taken back if another episode is loaded before it rings.
     */
    fun armEndOfChapter(chapterIndex: Int, episodeId: String, stopAtMs: Long?) {
        cancel()
        val armed = SleepTimerState(endOfChapterIndex = chapterIndex)
        if (stopAtMs == null) {
            isBellBacked = true
            bell.arm()
        }
        timerState.value = armed

        countdown = scope.launch {
            if (stopAtMs == null) {
                connection.playbackState.first { it.isConnected && it.episodeId != episodeId }
            } else {
                watchForTheEndOfTheChapter(episodeId, stopAtMs)
            }
            // Only if what is published is still this arming's: the line above can return on one
            // thread while another arming is publishing its own state on another.
            if (timerState.compareAndSet(armed, SleepTimerState()) && stopAtMs == null) bell.disarm()
        }
    }

    /**
     * Waits for the playhead to settle the matter, fading out and pausing if it gets there.
     *
     * A loop because a fade can be abandoned — the listener skipped back — and the chapter is then
     * still to be stopped at the end of.
     *
     * @param episodeId the episode the boundary is in.
     * @param stopAtMs the boundary.
     */
    private suspend fun watchForTheEndOfTheChapter(episodeId: String, stopAtMs: Long) {
        do {
            val deciding = connection.playbackState.first { playback ->
                // A state from before the controller connected names no episode at all, and is not
                // the episode changing.
                playback.isConnected && (
                    playback.episodeId != episodeId ||
                        playback.positionMs > stopAtMs + SEEK_PAST_TOLERANCE_MS ||
                        (playback.isPlaying && playback.positionMs >= playback.fadeFromMs(stopAtMs))
                    )
            }
            val playedThere = deciding.episodeId == episodeId &&
                deciding.positionMs <= stopAtMs + SEEK_PAST_TOLERANCE_MS
            if (!playedThere) return

            val listeningLeftMs = ((stopAtMs - deciding.positionMs) / deciding.audibleSpeed).toLong()
            val finished = fadeOutAndPause(
                durationMs = listeningLeftMs.coerceIn(MIN_FADE_DURATION_MS, FADE_DURATION_MS),
                abandonIf = {
                    val now = connection.playbackState.value
                    now.episodeId != episodeId || now.positionMs < now.fadeFromMs(stopAtMs)
                },
            )
        } while (!finished)
    }

    /**
     * Where a fade has to start to end on [stopAtMs]: [FADE_DURATION_MS] of *listening* before it,
     * which is more of the episode the faster it is being played.
     *
     * @param stopAtMs the boundary.
     * @return the position, in the episode's own milliseconds.
     */
    private fun PlaybackState.fadeFromMs(stopAtMs: Long): Long =
        stopAtMs - (FADE_DURATION_MS * audibleSpeed).toLong()

    /** The speed, kept off zero so that it can be divided by. */
    private val PlaybackState.audibleSpeed: Float get() = speed.coerceAtLeast(MIN_SPEED)

    /**
     * Adds time to a running countdown.
     *
     * What a shake means: "I am still awake". Does nothing when no countdown is running, including
     * for the end-of-episode and end-of-chapter options — there is no number there to add to, and
     * reinterpreting a shake as "switch to a timer" would be the app deciding something the user
     * did not say.
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
        isBellBacked = false
        bell.disarm()
        timerState.value = SleepTimerState()
    }

    /**
     * Fades the player out and pauses it.
     *
     * The volume is put back whatever happens — after the pause, so that the next thing the user
     * plays, tomorrow morning, having forgotten all of this, starts at full volume; and just as
     * much when the fade is cancelled or abandoned half-way, because "turn the timer off" pressed
     * during the fade must not leave the player at a third of its volume for good.
     *
     * @param durationMs how long the fade takes.
     * @param abandonIf asked before each step; true stops the fade without pausing.
     * @return true when the player was paused, false when the fade was abandoned.
     */
    private suspend fun fadeOutAndPause(
        durationMs: Long = FADE_DURATION_MS,
        abandonIf: () -> Boolean = { false },
    ): Boolean {
        try {
            repeat(FADE_STEPS) { step ->
                if (abandonIf()) return false
                connection.setVolume(1f - (step + 1).toFloat() / FADE_STEPS)
                delay(durationMs / FADE_STEPS)
            }
            connection.pause()
            return true
        } finally {
            withContext(NonCancellable) { connection.setVolume(1f) }
        }
    }

    private companion object {
        /** How often the countdown publishes; a sleep timer is read in minutes, ticked in seconds. */
        const val TICK_MS = 1_000L

        /** Long enough to be a fade rather than a cut, short enough not to be a fifth of a chapter. */
        const val FADE_DURATION_MS = 10_000L

        /** Steps in the fade; enough that it is not audibly stepped on any device. */
        const val FADE_STEPS = 40

        /** The shortest fade; under this it is a cut, whatever it is called. */
        const val MIN_FADE_DURATION_MS = 1_000L

        /** The slowest speed the fade arithmetic will believe. */
        const val MIN_SPEED = 0.1f

        /**
         * How far past a chapter's end the playhead may be found and still have *played* there.
         *
         * Wider than the gap between two position ticks at the fastest speed on offer, so playback
         * is never mistaken for a seek; far narrower than any seek a thumb makes on purpose.
         */
        const val SEEK_PAST_TOLERANCE_MS = 5_000L
    }
}

package md.borisveriga.megapodcastplayer.wear.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.PhonePlayerClient
import md.borisveriga.megapodcastplayer.wear.data.ReceivedSnapshot
import md.borisveriga.megapodcastplayer.wear.data.WatchHints

/**
 * Drives the watch's screen, which is a remote control for the phone's player.
 *
 * Every button becomes a [WearCommand] and goes to the phone; the watch decides nothing about
 * playback itself and learns the result from the phone's next snapshot. Two things are still
 * computed locally. The position ticks between the phone's publishes so the bar moves without a
 * Bluetooth write per second, and a scrub in progress is held here rather than sent continuously.
 *
 * @property client the connection to the phone.
 * @property hints what the watch has already explained once.
 */
@HiltViewModel
class WatchPlayerViewModel @Inject constructor(
    private val client: PhonePlayerClient,
    private val hints: WatchHints,
) : ViewModel() {

    /** Set when a command could not be delivered; cleared as soon as one gets through. */
    private val lastCommandFailed = MutableStateFlow(false)

    /** Where the user has dragged the progress bar, or null when they are not touching it. */
    private val scrub = MutableStateFlow<ScrubState?>(null)

    /** The level the wearer has turned to, or null when the phone's own reading is the truth. */
    private val volume = MutableStateFlow<VolumeAdjustment?>(null)

    /** True while the volume row holds the bezel; see [WatchPlayerUiState.isAdjustingVolume]. */
    private val volumeEngaged = MutableStateFlow(false)

    /** The throttled send of [volume], and the timer that lets go of the bezel; see [setVolume]. */
    private var volumeSendJob: Job? = null
    private var volumeReleaseJob: Job? = null

    /** When the last `SetVolume` went out, which is what the throttle measures against. */
    private var lastVolumeSentAtElapsedMs = 0L

    /** True for a few seconds after a moment is marked; see [WatchPlayerUiState.momentSaved]. */
    private val momentSaved = MutableStateFlow(false)

    /** True while the first scrub of this watch's life is being explained; see [WatchHints]. */
    private val scrubHintVisible = MutableStateFlow(false)

    /** The same, for the first time the volume bar is taken hold of. */
    private val volumeHintVisible = MutableStateFlow(false)

    /**
     * The things the screen says over the top of what is playing.
     *
     * Grouped for the reason [phone] is grouped: `combine` gives typed lambdas only up to five
     * sources, and these are the same kind of thing — a sentence the screen shows for a moment
     * and then takes away.
     */
    private val cues = combine(momentSaved, scrubHintVisible, volumeHintVisible, ::Cues)

    /** The volume the wearer is setting, and whether the bezel is theirs to set it with. */
    private val volumeState = combine(volume, volumeEngaged, ::VolumeMode)

    /**
     * What the phone is doing, and when it said so.
     *
     * Grouped because these three change together and because `combine` gives typed lambdas only up
     * to five sources.
     */
    private val phone = combine(
        client.phoneLink.onStart { emit(PhoneLink.CHECKING) },
        client.snapshots,
        elapsedRealtimeTicker(),
    ) { link, received, nowElapsedMs -> PhoneState(link, received, nowElapsedMs) }

    /**
     * Everything the screen draws, computed once per change of any input.
     *
     * Private, and split in two below, because its inputs move at two very different rates. The
     * clock in [phone] ticks every second, so this flow emits every second — and the screen is a
     * list whose rows must not be rebuilt for a clock tick, so the screen is never handed this.
     */
    private val frame: StateFlow<WatchPlayerFrame> = combine(
        phone,
        lastCommandFailed,
        scrub,
        cues,
        volumeState,
    ) { phone, failed, scrubState, cues, volume ->
        watchPlayerFrame(
            link = phone.link,
            received = phone.received,
            nowElapsedMs = phone.nowElapsedMs,
            lastCommandFailed = failed,
            scrub = scrubState,
            volume = volume.adjustment,
            isAdjustingVolume = volume.engaged,
            momentSaved = cues.momentSaved,
            showsScrubHint = cues.scrubHint,
            showsVolumeHint = cues.volumeHint,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = WatchPlayerFrame(),
    )

    /**
     * What the screen draws.
     *
     * Emits only when something other than the clock changed. [frame] emits every second, but a
     * state flow drops a value equal to the one it holds, and [WatchPlayerUiState] carries nothing
     * that moves by itself — so the list, which collects this, sits still while an episode plays.
     * That is what keeps a scroll through it smooth; see [position] for the part that moves.
     *
     * No timeout of its own: [frame] is what keeps the Data Layer listeners warm across a brief
     * absence, and a second timeout here would only stack on top of it.
     */
    val uiState: StateFlow<WatchPlayerUiState> = frame
        .map { it.uiState }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = frame.value.uiState,
        )

    /**
     * What the bar draws: the position, and only the position.
     *
     * Collected by the one composable that shows it, so a clock tick recomposes a time label and
     * nothing else. See [uiState].
     */
    val position: StateFlow<PlaybackPosition> = frame
        .map { it.position }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = frame.value.position,
        )

    init {
        // The cached data item may predate the phone being restarted, and asking also starts the
        // phone's process if it is not running — so the first thing the watch does is ask.
        send(WearCommand.RequestState)
    }

    /** Starts or pauses playback on the phone. */
    fun togglePlayPause() = send(WearCommand.TogglePlayPause)

    /** Jumps forward by the interval configured on the phone. */
    fun skipForward() = send(WearCommand.SkipForward)

    /** Jumps back by the interval configured on the phone; see [skipForward]. */
    fun skipBack() = send(WearCommand.SkipBack)

    /** Moves to the next queued episode. */
    fun skipToNext() = send(WearCommand.SkipToNext)

    /** Restarts the episode, or moves to the previous one. */
    fun skipToPrevious() = send(WearCommand.SkipToPrevious)

    /**
     * Advances to the next playback speed.
     *
     * The phone owns the preference, so cycling it there both stores and applies it.
     */
    fun cycleSpeed() = send(WearCommand.CycleSpeed)

    /**
     * Seeks within the current episode.
     *
     * @param positionMs the absolute position; the phone clamps it.
     */
    fun seekTo(positionMs: Long) = send(WearCommand.SeekTo(positionMs))

    /**
     * Takes hold of the progress bar, starting from wherever it currently reads.
     *
     * Seeding from the displayed position rather than the last snapshot is deliberate: the bar has
     * been ticking forward locally since that snapshot, and starting anywhere else would make the
     * bar jump the instant it is touched.
     */
    fun beginScrub() {
        if (!frame.value.uiState.canScrub) return
        // The bezel has one owner. Taking it for the scrubber gives it up for the volume row,
        // which would otherwise keep its focus and swallow the turns meant for the position.
        releaseVolume()
        scrub.value = ScrubState(positionMs = frame.value.position.positionMs)
        explainScrubbingOnce()
    }

    /**
     * Takes hold of the volume bar, so that the bezel changes the volume rather than scrolling.
     *
     * The other half of the exclusion [beginScrub] enforces: a scrub in progress is abandoned
     * rather than left holding a focus it no longer has.
     */
    fun beginVolume() {
        if (!frame.value.uiState.canSetVolume) return
        scrub.value = null
        scrubHintVisible.value = false
        volumeEngaged.value = true
        // Seeded from what the row is showing, so the bar does not jump the instant it is touched.
        volume.value = VolumeAdjustment(level = frame.value.uiState.volumeLevel, sentAtElapsedMs = null)
        explainVolumeOnce()
        restartVolumeRelease()
    }

    /** Lets go of the volume bar, handing the bezel back to the list. */
    fun endVolume() = releaseVolume()

    /**
     * Moves the volume by whole steps of the phone's own scale.
     *
     * A step is a step the phone has: the bezel's detents and the phone's volume keys then agree
     * about how much quieter "one quieter" is. Called by the row's minus and plus buttons as well
     * as by the bezel, which is why it does not require the bar to be held.
     *
     * @param steps how many steps to move; negative is quieter.
     */
    fun adjustVolumeBy(steps: Int) {
        // Counted from the pending level rather than from the screen's. A bezel delivers its turns
        // as a burst on one thread, while [frame] is a combine that catches up a dispatch later —
        // so two steps read from the screen would both start at the same place and one of them
        // would be lost. The pending level is set synchronously by the step before it.
        val current = volume.value?.level ?: frame.value.uiState.volumeLevel
        setVolume(current + steps)
    }

    /**
     * Sets an absolute volume level, clamped to the phone's scale.
     *
     * The level is shown at once and sent on a throttle — never a debounce. Someone turning the
     * bezel is listening for the change while they turn, so a level that waited for them to stop
     * would be a control that felt broken until they let go. `SetVolume` carries an absolute
     * level, so the values the throttle drops cost nothing: the last one to arrive is the whole
     * truth.
     *
     * @param level the level to set, on the phone's `0..maxVolume` scale.
     */
    fun setVolume(level: Int) {
        val maxVolume = frame.value.uiState.snapshot.maxVolume
        if (maxVolume <= 0) return

        val target = level.coerceIn(0, maxVolume)
        // A turn that changes nothing — the bezel at an end stop, or a button pressed twice at
        // zero — is not worth a message. The phone is already there.
        if (target == (volume.value?.level ?: frame.value.uiState.volumeLevel)) return

        volume.value = VolumeAdjustment(level = target, sentAtElapsedMs = null)
        restartVolumeRelease()
        scheduleVolumeSend(target)
    }

    /**
     * Sends [level] now if the throttle allows, otherwise when it next does.
     *
     * One pending send at a time, replaced rather than queued: a wrist mid-turn produces a level
     * every few milliseconds and only the newest one means anything. The trailing send is what
     * guarantees the level the wearer settled on always reaches the phone, which is the case
     * `sample` would not cover even if it were not `@FlowPreview`.
     */
    private fun scheduleVolumeSend(level: Int) {
        volumeSendJob?.cancel()
        volumeSendJob = viewModelScope.launch {
            val sinceLastSend = SystemClock.elapsedRealtime() - lastVolumeSentAtElapsedMs
            val wait = VOLUME_SEND_INTERVAL_MS - sinceLastSend
            if (wait > 0L) delay(wait)

            val sentAt = SystemClock.elapsedRealtime()
            lastVolumeSentAtElapsedMs = sentAt
            // Stamped before the reply can arrive, so the held reading covers the whole round trip.
            val sent = VolumeAdjustment(level = level, sentAtElapsedMs = sentAt)
            volume.value = sent
            lastCommandFailed.value = !client.send(WearCommand.SetVolume(level))

            // Let go once the hold is over, so that a volume changed on the phone afterwards is
            // shown rather than argued with. A later step cancels this job along with its send,
            // which is what keeps a turn in progress from being cleared out from under itself.
            delay(VOLUME_HOLD_MS)
            volume.compareAndSet(expect = sent, update = null)
        }
    }

    /**
     * Restarts the wait after which the bezel goes back to the list.
     *
     * A mode nobody leaves is a scroll that stopped working, and unlike the scrubber there is no
     * commit here to leave it by: every step is already applied. So stillness ends it.
     */
    private fun restartVolumeRelease() {
        volumeReleaseJob?.cancel()
        volumeReleaseJob = viewModelScope.launch {
            delay(VOLUME_RELEASE_MS)
            volumeEngaged.value = false
        }
    }

    /**
     * Leaves volume mode and stops the timer that would have left it.
     *
     * A level that was never sent is dropped with the mode. That is the bar taken hold of and let
     * go without a turn: keeping its seeded level would pin the row to a reading of its own, and
     * the phone's volume — moved by its own keys, by another app, by anything — would stop
     * reaching it. A level that *was* sent is left alone; its own hold is what lets go of it, and
     * only once the phone has had its say.
     */
    private fun releaseVolume() {
        volumeReleaseJob?.cancel()
        volumeEngaged.value = false
        volumeHintVisible.value = false
        if (volume.value?.sentAtElapsedMs == null) volume.value = null
    }

    /**
     * Says what the bezel now does, the first time the volume bar is taken hold of on this watch.
     *
     * Marked seen as it is shown, for the reason [explainScrubbingOnce] gives.
     */
    private fun explainVolumeOnce() {
        viewModelScope.launch {
            if (hints.hasSeenVolumeHint()) return@launch
            volumeHintVisible.value = true
            hints.markVolumeHintSeen()
        }
    }

    /**
     * Says how to scrub, the first time anyone does it on this watch.
     *
     * Marked seen as it is shown rather than when it is dismissed. The hint is a sentence, not a
     * dialog: there is nothing to acknowledge, and a wearer who taps the bar and immediately taps
     * away has still been told. Recording it later would mean a hint that came back after every
     * scrub the wearer abandoned, which is the failure mode a one-time hint exists to avoid.
     */
    private fun explainScrubbingOnce() {
        viewModelScope.launch {
            if (hints.hasSeenScrubHint()) return@launch
            scrubHintVisible.value = true
            hints.markScrubHintSeen()
        }
    }

    /**
     * Moves the scrub position, clamped to the episode.
     *
     * Nothing is sent anywhere here. The seek happens once, on [commitScrub].
     *
     * @param deltaMs how far to move; negative rewinds.
     */
    fun scrubBy(deltaMs: Long) {
        val duration = frame.value.uiState.snapshot.knownDurationMs ?: return
        val current = scrub.value ?: return
        if (current.committedAtElapsedMs != null) return

        scrub.value = current.copy(
            positionMs = (current.positionMs + deltaMs).coerceIn(0L, duration),
        )
        // The bar has moved, so the wearer has worked out how; the sentence has done its job and
        // is now covering the times it was explaining.
        scrubHintVisible.value = false
    }

    /** Abandons a scrub without seeking, leaving playback where it was. */
    fun cancelScrub() {
        scrub.value = null
        scrubHintVisible.value = false
    }

    /**
     * Applies the scrubbed position.
     *
     * The scrub is kept, stamped with the moment the command went out, so the bar stays where the
     * user put it across the Bluetooth round trip instead of bouncing back; see [SEEK_HOLD_MS].
     */
    fun commitScrub() {
        val current = scrub.value ?: return
        if (current.committedAtElapsedMs != null) return

        val committed = current.copy(committedAtElapsedMs = SystemClock.elapsedRealtime())
        scrub.value = committed
        scrubHintVisible.value = false
        seekTo(committed.positionMs)

        viewModelScope.launch {
            delay(SEEK_HOLD_MS)
            // Compared by identity of the whole value: a scrub the user has since restarted is a
            // different one, and must not be cleared out from under them.
            scrub.compareAndSet(expect = committed, update = null)
        }
    }

    /**
     * Marks the moment the wearer just heard.
     *
     * The watch does not know where the phone's playhead is — what the bar shows is an
     * extrapolation of a snapshot up to a second old — so it asks the phone to mark its own
     * position rather than sending one. A phone that cannot be reached is a phone that is not
     * playing, so there is nothing to queue for later; the failure is shown like any other.
     */
    fun markMoment() {
        viewModelScope.launch {
            val reached = client.send(WearCommand.MarkMoment)
            lastCommandFailed.value = !reached
            if (reached) confirmMoment()
        }
    }

    /**
     * Shows the "saved" confirmation for a moment, then takes it away.
     *
     * Held for a few seconds rather than until the next state change: everything else on this
     * screen moves once a second, and a confirmation that outlived its press would be attached to
     * whatever the wearer did next.
     */
    private fun confirmMoment() {
        momentSaved.value = true
        viewModelScope.launch {
            delay(MOMENT_CONFIRM_MS)
            momentSaved.value = false
        }
    }

    /**
     * Plays an episode on the phone, in place of whatever is playing.
     *
     * @param episodeId the episode, as it arrived in the snapshot's queue or downloaded list.
     */
    fun playOnPhone(episodeId: String) = send(WearCommand.PlayEpisode(episodeId))

    /**
     * Puts a downloaded episode at the end of the phone's queue, leaving playback alone.
     *
     * Nothing is confirmed here. The phone republishes after every command, and the episode leaving
     * the downloaded list for the queue a moment later is the confirmation — a truer one than a
     * label this could have shown, because it is the phone's own state saying so.
     *
     * @param episodeId the episode, as it arrived in the snapshot's downloaded list.
     */
    fun queueOnPhone(episodeId: String) = send(WearCommand.QueueEpisode(episodeId))

    /** Asks the phone to republish its state, for the pull-to-retry on the disconnected screen. */
    fun retry() = send(WearCommand.RequestState)

    /**
     * Sends a command and records whether it got through.
     *
     * Delivery failure is surfaced rather than swallowed: the watch has no way to make the command
     * happen later, so a button that silently did nothing would just be pressed again.
     */
    private fun send(command: WearCommand) {
        viewModelScope.launch {
            lastCommandFailed.value = !client.send(command)
        }
    }

    /**
     * The phone's half of the screen state.
     *
     * @property link whether it can be reached.
     * @property received its last snapshot, or null if it has never spoken.
     * @property nowElapsedMs the watch's clock, for extrapolating [received].
     */
    private data class PhoneState(
        val link: PhoneLink,
        val received: ReceivedSnapshot?,
        val nowElapsedMs: Long,
    )

    /**
     * The transient cues drawn over the player.
     *
     * @property momentSaved true while the mark-a-moment confirmation is up.
     * @property scrubHint true while the first-scrub explanation is up.
     * @property volumeHint true while the first-volume explanation is up.
     */
    private data class Cues(
        val momentSaved: Boolean,
        val scrubHint: Boolean,
        val volumeHint: Boolean,
    )

    /**
     * The volume half of the screen state.
     *
     * @property adjustment the level the wearer turned to, or null when the phone's own reading
     *   is what the row should show.
     * @property engaged whether the row currently owns the bezel.
     */
    private data class VolumeMode(
        val adjustment: VolumeAdjustment?,
        val engaged: Boolean,
    )

    private companion object {
        /** Keeps the Data Layer listeners attached while the screen briefly goes away. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** How often the extrapolated position is recomputed. */
        const val POSITION_TICK_MS = 1_000L

        /** How long the "moment saved" confirmation stays on the screen. */
        const val MOMENT_CONFIRM_MS = 3_000L

        /**
         * The shortest gap between two `SetVolume` messages.
         *
         * Short enough that a turn of the bezel is heard while it is still turning, long enough
         * that a fast turn does not put a Bluetooth write behind every detent. The level is
         * absolute, so the values this drops are values nobody needed.
         */
        const val VOLUME_SEND_INTERVAL_MS = 150L

        /**
         * How long the volume row keeps the bezel after the last turn.
         *
         * Long enough to think between two turns, short enough that a wrist dropped mid-adjustment
         * is scrolling again by the time it is raised.
         */
        const val VOLUME_RELEASE_MS = 4_000L

        /**
         * Emits the watch's elapsed-realtime clock once a second.
         *
         * This is what advances the progress bar between the phone's publishes. It runs regardless
         * of whether anything is playing, because a paused snapshot simply extrapolates to itself,
         * and one timer is cheaper to reason about than one that has to be started and stopped.
         */
        fun elapsedRealtimeTicker() = flow {
            while (currentCoroutineContext().isActive) {
                emit(SystemClock.elapsedRealtime())
                delay(POSITION_TICK_MS)
            }
        }
    }
}

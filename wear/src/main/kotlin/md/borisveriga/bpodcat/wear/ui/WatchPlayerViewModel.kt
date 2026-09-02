package md.borisveriga.bpodcat.wear.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.wearprotocol.WearCommand
import md.borisveriga.bpodcat.wear.data.PhoneLink
import md.borisveriga.bpodcat.wear.data.PhonePlayerClient
import md.borisveriga.bpodcat.wear.data.WatchArtwork

/**
 * Drives the watch's remote control.
 *
 * Holds no playback state of its own — it cannot, since the audio is on the phone. Every button
 * turns into a [WearCommand] and the screen only changes once the phone has said it did.
 *
 * Two things are computed locally. The playback position ticks between the phone's publishes so the
 * progress bar moves smoothly without a Bluetooth write per second. And a scrub in progress is held
 * here rather than sent continuously: dragging along a one-hour episode would otherwise put hundreds
 * of seeks on the link, so only the position the user settles on is sent.
 *
 * @property client the connection to the phone.
 * @property artworkSource cover art the phone published, decoded off the same data item.
 */
@HiltViewModel
class WatchPlayerViewModel @Inject constructor(
    private val client: PhonePlayerClient,
    artworkSource: WatchArtwork,
) : ViewModel() {

    /** Set when a command could not be delivered; cleared as soon as one gets through. */
    private val lastCommandFailed = MutableStateFlow(false)

    /** Where the user has dragged the progress bar, or null when they are not touching it. */
    private val scrub = MutableStateFlow<ScrubState?>(null)

    val uiState: StateFlow<WatchPlayerUiState> = combine(
        client.phoneLink.onStart { emit(PhoneLink.CHECKING) },
        // Paired up because both read the same data item, and combining them here keeps the outer
        // `combine` within the arity kotlinx.coroutines gives typed lambdas for.
        combine(client.snapshots, artworkSource.artwork) { received, artwork -> received to artwork },
        elapsedRealtimeTicker(),
        lastCommandFailed,
        scrub,
    ) { link, (received, artwork), nowElapsedMs, failed, scrubState ->
        watchPlayerUiState(link, received, nowElapsedMs, failed, scrubState, artwork)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = WatchPlayerUiState(),
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

    /** Jumps back by the interval configured on the phone. */
    fun skipBack() = send(WearCommand.SkipBack)

    /** Moves to the next queued episode. */
    fun skipToNext() = send(WearCommand.SkipToNext)

    /** Restarts the episode, or moves to the previous one if already near the start. */
    fun skipToPrevious() = send(WearCommand.SkipToPrevious)

    /** Advances the phone to its next playback speed. */
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
        if (!uiState.value.canScrub) return
        scrub.value = ScrubState(positionMs = uiState.value.positionMs)
    }

    /**
     * Moves the scrub position, clamped to the episode.
     *
     * Nothing is sent to the phone here. The command goes out once, on [commitScrub].
     *
     * @param deltaMs how far to move; negative rewinds.
     */
    fun scrubBy(deltaMs: Long) {
        val duration = uiState.value.snapshot.knownDurationMs ?: return
        val current = scrub.value ?: return
        if (current.committedAtElapsedMs != null) return

        scrub.value = current.copy(
            positionMs = (current.positionMs + deltaMs).coerceIn(0L, duration),
        )
    }

    /** Abandons a scrub without seeking, leaving playback where it was. */
    fun cancelScrub() {
        scrub.value = null
    }

    /**
     * Sends the scrubbed position to the phone.
     *
     * The scrub is kept, stamped with the moment the command went out, so the bar stays where the
     * user put it across the Bluetooth round trip instead of bouncing back; see [SEEK_HOLD_MS]. It
     * is dropped once the hold expires, by which point either the phone has confirmed or it never
     * will.
     */
    fun commitScrub() {
        val current = scrub.value ?: return
        if (current.committedAtElapsedMs != null) return

        val committed = current.copy(committedAtElapsedMs = SystemClock.elapsedRealtime())
        scrub.value = committed
        seekTo(committed.positionMs)

        viewModelScope.launch {
            delay(SEEK_HOLD_MS)
            // Compared by identity of the whole value: a scrub the user has since restarted is a
            // different one, and must not be cleared out from under them.
            scrub.compareAndSet(expect = committed, update = null)
        }
    }

    /**
     * Plays a queued episode.
     *
     * @param episodeId the episode, as it arrived in the snapshot's queue.
     */
    fun playQueued(episodeId: String) = send(WearCommand.PlayEpisode(episodeId))

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

    private companion object {
        /** Keeps the Data Layer listeners attached while the screen briefly goes away. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** How often the extrapolated position is recomputed. */
        const val POSITION_TICK_MS = 1_000L

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

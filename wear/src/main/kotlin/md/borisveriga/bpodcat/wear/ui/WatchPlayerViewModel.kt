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

/**
 * Drives the watch's remote control.
 *
 * Holds no playback state of its own — it cannot, since the audio is on the phone. Every button
 * turns into a [WearCommand] and the screen only changes once the phone has said it did.
 *
 * The one thing computed locally is the playback position, which ticks between the phone's
 * publishes so the progress ring moves smoothly without a Bluetooth write per second.
 *
 * @property client the connection to the phone.
 */
@HiltViewModel
class WatchPlayerViewModel @Inject constructor(
    private val client: PhonePlayerClient,
) : ViewModel() {

    /** Set when a command could not be delivered; cleared as soon as one gets through. */
    private val lastCommandFailed = MutableStateFlow(false)

    val uiState: StateFlow<WatchPlayerUiState> = combine(
        client.phoneLink.onStart { emit(PhoneLink.CHECKING) },
        client.snapshots,
        elapsedRealtimeTicker(),
        lastCommandFailed,
    ) { link, received, nowElapsedMs, failed ->
        watchPlayerUiState(link, received, nowElapsedMs, failed)
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
         * This is what advances the progress ring between the phone's publishes. It runs regardless
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

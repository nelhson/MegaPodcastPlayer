package md.borisveriga.megapodcastplayer.wear.ui

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineEpisode
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.PhonePlayerClient
import md.borisveriga.megapodcastplayer.wear.data.PositionReporter
import md.borisveriga.megapodcastplayer.wear.data.ReceivedSnapshot
import md.borisveriga.megapodcastplayer.wear.data.StoredEpisode
import md.borisveriga.megapodcastplayer.wear.data.TransferProgress
import md.borisveriga.megapodcastplayer.wear.data.WatchEpisodeStore
import md.borisveriga.megapodcastplayer.wear.data.WatchLibrary
import md.borisveriga.megapodcastplayer.wear.playback.WatchPlayback
import md.borisveriga.megapodcastplayer.wear.playback.WatchPlaybackState

/**
 * Drives the watch's screen, which is a remote control and, when asked, a player.
 *
 * Which of the two it is at any moment is [PlaybackSource]: every control below routes to the phone
 * or to the watch's own player depending on where the audio is actually coming from. That is the
 * whole of the switch — there is no second screen and no mode to enter, because a wrist has room for
 * one set of buttons and they should always drive the thing making the noise.
 *
 * Two things are still computed locally for the phone's playback. The position ticks between the
 * phone's publishes so the bar moves without a Bluetooth write per second, and a scrub in progress
 * is held here rather than sent continuously.
 *
 * @property client the connection to the phone.
 * @property playback the watch's own player.
 * @property store the episodes the watch holds.
 * @property library what the phone has offered to send.
 * @property reporter carries positions played here back to the phone.
 */
@HiltViewModel
class WatchPlayerViewModel @Inject constructor(
    private val client: PhonePlayerClient,
    private val playback: WatchPlayback,
    private val store: WatchEpisodeStore,
    private val reporter: PositionReporter,
    library: WatchLibrary,
) : ViewModel() {

    /** Set when a command could not be delivered; cleared as soon as one gets through. */
    private val lastCommandFailed = MutableStateFlow(false)

    /** Where the user has dragged the progress bar, or null when they are not touching it. */
    private val scrub = MutableStateFlow<ScrubState?>(null)

    /**
     * What the phone is doing, and when it said so.
     *
     * Grouped because these three change together and because `combine` gives typed lambdas only up
     * to five sources; the screen needs rather more than five things now.
     */
    private val phone = combine(
        client.phoneLink.onStart { emit(PhoneLink.CHECKING) },
        client.snapshots,
        elapsedRealtimeTicker(),
    ) { link, received, nowElapsedMs -> PhoneState(link, received, nowElapsedMs) }

    /** What the watch itself holds and is playing. */
    private val watch = combine(
        playback.state,
        store.episodes,
        store.transfers,
        library.library,
    ) { local, stored, transfers, offered -> WatchState(local, stored, transfers, offered.episodes) }

    val uiState: StateFlow<WatchPlayerUiState> = combine(
        phone,
        watch,
        lastCommandFailed,
        scrub,
    ) { phone, watch, failed, scrubState ->
        watchPlayerUiState(
            link = phone.link,
            received = phone.received,
            nowElapsedMs = phone.nowElapsedMs,
            lastCommandFailed = failed,
            scrub = scrubState,
            local = watch.local,
            stored = watch.stored,
            offered = watch.offered,
            transfers = watch.transfers,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = WatchPlayerUiState(),
    )

    init {
        // The cached data item may predate the phone being restarted, and asking also starts the
        // phone's process if it is not running — so the first thing the watch does is ask.
        send(WearCommand.RequestState)

        // Anything played out of range is still owed to the phone; this is what settles the debt.
        viewModelScope.launch {
            client.phoneLink
                .distinctUntilChanged()
                .filter { it == PhoneLink.CONNECTED }
                .collect { reporter.flush() }
        }
    }

    /** Starts or pauses playback, wherever it is happening. */
    fun togglePlayPause() = onSource(
        onPhone = { send(WearCommand.TogglePlayPause) },
        onWatch = { playback.togglePlayPause() },
    )

    /** Jumps forward by the interval configured on the phone, on whichever device is playing. */
    fun skipForward() = onSource(
        onPhone = { send(WearCommand.SkipForward) },
        onWatch = { playback.skipForward(uiState.value.snapshot.skipForwardMs) },
    )

    /** Jumps back by the interval configured on the phone; see [skipForward]. */
    fun skipBack() = onSource(
        onPhone = { send(WearCommand.SkipBack) },
        onWatch = { playback.skipBack(uiState.value.snapshot.skipBackMs) },
    )

    /**
     * Moves to the next queued episode.
     *
     * Only the phone has a queue: the watch holds a handful of episodes chosen one at a time, and
     * "next" among them is not a thing the wearer asked for. The screen hides this button during
     * local playback, and this guards the case where something else calls it.
     */
    fun skipToNext() {
        if (uiState.value.source == PlaybackSource.PHONE) send(WearCommand.SkipToNext)
    }

    /** Restarts the episode, or moves to the previous one; the phone's queue only, as [skipToNext]. */
    fun skipToPrevious() {
        if (uiState.value.source == PlaybackSource.PHONE) send(WearCommand.SkipToPrevious)
    }

    /**
     * Advances to the next playback speed.
     *
     * The phone owns the preference, so cycling it there both stores and applies it. The watch's own
     * player has nowhere to store one — its speed lasts as long as the episode does — but it steps
     * through the same list, so the button means the same thing on both.
     */
    fun cycleSpeed() = onSource(
        onPhone = { send(WearCommand.CycleSpeed) },
        onWatch = {
            playback.setSpeed(PlaybackSettings(speed = uiState.value.snapshot.speed).nextSpeed())
        },
    )

    /**
     * Seeks within the current episode.
     *
     * @param positionMs the absolute position; the far end clamps it.
     */
    fun seekTo(positionMs: Long) = onSource(
        onPhone = { send(WearCommand.SeekTo(positionMs)) },
        onWatch = { playback.seekTo(positionMs) },
    )

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
     * Nothing is sent anywhere here. The seek happens once, on [commitScrub].
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
     * Applies the scrubbed position.
     *
     * The scrub is kept, stamped with the moment the command went out, so the bar stays where the
     * user put it across the Bluetooth round trip instead of bouncing back; see [SEEK_HOLD_MS]. On
     * the watch's own player there is no round trip, but the hold costs nothing and keeps one code
     * path.
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
     * Plays a queued episode on the phone.
     *
     * @param episodeId the episode, as it arrived in the snapshot's queue.
     */
    fun playQueued(episodeId: String) = send(WearCommand.PlayEpisode(episodeId))

    /**
     * Plays an episode the watch holds, on the watch.
     *
     * This is the tap that turns a remote control into a player. Nothing is asked of the phone —
     * that is the whole point — so it works with the phone switched off.
     *
     * @param episode the stored episode to start.
     */
    fun playOnWatch(episode: StoredEpisode) {
        viewModelScope.launch { playback.play(episode) }
    }

    /**
     * Hands the screen back to the phone by unloading the watch's player.
     *
     * The position is written down and reported by the playback service as it stops, so nothing is
     * lost by leaving.
     */
    fun backToPhone() {
        viewModelScope.launch { playback.stop() }
    }

    /**
     * Asks the phone to send an episode's audio over.
     *
     * The reply is not a message but a channel, minutes long; the screen learns it started when the
     * transfer appears in [WatchPlayerUiState.transfers].
     *
     * @param episodeId the episode, as it arrived in the offered library.
     */
    fun copyToWatch(episodeId: String) = send(WearCommand.CopyToWatch(episodeId))

    /**
     * Deletes an episode from the watch.
     *
     * Stops it first if it is the one playing: removing the file underneath a running player would
     * leave the screen showing controls for audio that has stopped existing.
     *
     * @param episodeId the episode to remove.
     */
    fun removeFromWatch(episodeId: String) {
        viewModelScope.launch {
            if (uiState.value.snapshot.episodeId == episodeId &&
                uiState.value.source == PlaybackSource.WATCH
            ) {
                playback.stop()
            }
            store.remove(episodeId)
        }
    }

    /** Deletes everything the watch holds, stopping local playback first. */
    fun removeAllFromWatch() {
        viewModelScope.launch {
            playback.stop()
            store.removeAll()
        }
    }

    /** Asks the phone to republish its state, for the pull-to-retry on the disconnected screen. */
    fun retry() = send(WearCommand.RequestState)

    /**
     * Runs whichever of two actions matches where the audio is.
     *
     * @param onPhone what to do when the phone is playing.
     * @param onWatch what to do when the watch is.
     */
    private fun onSource(onPhone: () -> Unit, onWatch: suspend () -> Unit) {
        if (uiState.value.source == PlaybackSource.WATCH) {
            viewModelScope.launch { onWatch() }
        } else {
            onPhone()
        }
    }

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
     * The watch's half.
     *
     * @property local what its own player is doing, or null.
     * @property stored the episodes it holds.
     * @property transfers copies arriving now.
     * @property offered what the phone says it could send.
     */
    private data class WatchState(
        val local: WatchPlaybackState?,
        val stored: List<StoredEpisode>,
        val transfers: Map<String, TransferProgress>,
        val offered: List<OfflineEpisode>,
    )

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

package md.borisveriga.megapodcastplayer.feature.player.embedded

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.media.NetworkStatus
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackProgressRecorder
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoIdOrNull

/**
 * State rendered by the embedded video screen.
 *
 * @property episode the episode being watched; null while loading, or once it has gone.
 * @property showTitle the show's name, for the line under the title.
 * @property videoId the video to embed, or null while the episode is unknown or is not a YouTube
 *   episode — in which case the screen has nothing to show and leaves.
 * @property startMs where the player is told to start.
 * @property isOnline whether there is a network to fetch the picture over. The embed is YouTube's
 *   own and streams from YouTube; with no network there is nothing to load it over, and the screen
 *   says so instead of drawing a player that would.
 * @property playerState what the player is doing, as the page last reported it.
 * @property error why the player would not play, or null while it would.
 * @property positionMs where the player is, as last reported.
 * @property durationMs the video's length, as last reported; null until known.
 */
data class EmbeddedVideoUiState(
    val episode: Episode? = null,
    val showTitle: String = "",
    val videoId: String? = null,
    val startMs: Long = 0L,
    val isOnline: Boolean = true,
    val playerState: EmbeddedPlayerState = EmbeddedPlayerState.UNSTARTED,
    val error: EmbeddedPlayerError? = null,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
) {
    /** Whether the page should be on screen at all: there is a video, a network, and no failure. */
    val showsPlayer: Boolean get() = videoId != null && isOnline && error == null
}

/**
 * Drives the embedded video screen: YouTube's own player, on a YouTube episode, under the official
 * source.
 *
 * Deliberately not a face of the app's player. The app's player is Media3 over an extracted stream,
 * which the official source forbids; this is YouTube's embed in a `WebView`, which it permits, and
 * the two share nothing — not the queue, not the notification, not the watch. What this does share
 * with the rest of the app is the *episode*: the position the user reached is written to the same
 * row the app's player writes to, so a show switched back to the extractor resumes where the embed
 * left off, and a video watched to its end is marked played as any episode is.
 *
 * The app's player is paused on entry. Two things playing at once is never what a tap meant, and
 * the embed cannot ask the player to yield the way a second Media3 item would.
 *
 * @property podcastRepository the episode and its show.
 * @property progressRecorder where the position is written: the same recorder the app's player
 *   writes through, so the two players agree on where an episode was left.
 * @property playbackRepository where the played flag is written.
 * @property connection the app's player, paused on entry and otherwise left alone.
 * @property networkStatus whether there is a network to fetch the embed over.
 * @param savedStateHandle carries the `episodeId` and `startMs` navigation arguments.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EmbeddedVideoViewModel @Inject constructor(
    private val podcastRepository: PodcastRepository,
    private val progressRecorder: PlaybackProgressRecorder,
    private val playbackRepository: PlaybackRepository,
    private val connection: PlaybackConnection,
    networkStatus: NetworkStatus,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The episode being watched, from the navigation route. */
    private val episodeId: String = checkNotNull(savedStateHandle[EPISODE_ID_ARG]) {
        "EmbeddedVideoViewModel requires an '$EPISODE_ID_ARG' navigation argument"
    }

    /**
     * Where the route asked to start, or [RESUME] to pick up where the episode was left.
     *
     * A moment names a second; a tap on an episode names none, and gets the stored position.
     */
    private val requestedStartMs: Long = savedStateHandle[START_MS_ARG] ?: RESUME

    /** What the page has reported: state, position, failure. Nothing here outlives the screen. */
    private val playerState = MutableStateFlow(PlayerReport())

    /** The position last written to the row, so a tick that changed little is not a write. */
    private var persistedPositionMs: Long = -1L

    /** The episode with its show's title, following the episode's show if it changes. */
    private val episodeWithShow = podcastRepository.observeEpisode(episodeId)
        .flatMapLatest { episode ->
            if (episode == null) {
                flowOf(null to "")
            } else {
                podcastRepository.observePodcast(episode.podcastId).map { episode to it?.title.orEmpty() }
            }
        }

    val uiState: StateFlow<EmbeddedVideoUiState> = combine(
        episodeWithShow,
        networkStatus.observeOnline(),
        playerState,
    ) { (episode, showTitle), isOnline, report ->
        EmbeddedVideoUiState(
            episode = episode,
            showTitle = showTitle,
            videoId = episode?.let { youTubeVideoIdOrNull(it.audioUrl) },
            startMs = startPositionFor(episode),
            isOnline = isOnline,
            playerState = report.state,
            error = report.error,
            positionMs = report.positionMs,
            durationMs = report.durationMs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = EmbeddedVideoUiState(),
    )

    /**
     * Where the player should start on [episode]: what the route asked for, else where the episode
     * was left, else the beginning.
     *
     * A finished episode starts over: its stored position is its last second, and resuming there
     * would be a screen that ends the moment it opens.
     */
    private fun startPositionFor(episode: Episode?): Long = when {
        requestedStartMs != RESUME -> requestedStartMs
        episode == null || episode.isPlayed -> 0L
        else -> episode.positionMs
    }

    /**
     * Called when the screen comes to the front.
     *
     * Pauses the app's player, so that an RSS episode playing in the background does not carry on
     * under the picture. Not stopped: what was playing is left loaded, a tap away in the bar.
     */
    fun enter() {
        viewModelScope.launch { connection.pause() }
    }

    /**
     * Takes in what the page reported.
     *
     * Called from the `WebView`'s JavaScript thread; see [EmbeddedPlayerBridge]. A tick is the one
     * event that also writes: the position goes to the episode's row every [PERSIST_EVERY_MS] of
     * movement, so that a process killed with the screen up has lost at most that much. The end of
     * the video marks the episode played, as the app's player does.
     *
     * @param event what the page said.
     */
    fun onEvent(event: EmbeddedPlayerEvent) {
        when (event) {
            EmbeddedPlayerEvent.Ready -> Unit

            is EmbeddedPlayerEvent.StateChanged -> {
                playerState.update { it.copy(state = event.state) }
                if (event.state == EmbeddedPlayerState.ENDED) markPlayed()
            }

            is EmbeddedPlayerEvent.Time -> {
                playerState.update {
                    it.copy(
                        positionMs = event.positionMs,
                        durationMs = event.durationMs.takeIf { duration -> duration > 0L },
                    )
                }
                val moved = abs(event.positionMs - persistedPositionMs)
                if (persistedPositionMs < 0L || moved >= PERSIST_EVERY_MS) persistPosition()
            }

            is EmbeddedPlayerEvent.Failed -> playerState.update { it.copy(error = event.error) }
        }
    }

    /**
     * Writes the position the player has reached, whatever the last tick wrote.
     *
     * Called when the screen leaves the front and when it is taken down: the last few seconds
     * before a pause are the ones the user most wants to come back to.
     */
    fun savePosition() {
        persistPosition()
    }

    private fun persistPosition() {
        val report = playerState.value
        if (report.positionMs <= 0L && report.durationMs == null) return
        persistedPositionMs = report.positionMs
        viewModelScope.launch {
            progressRecorder.recordPosition(episodeId, report.positionMs, report.durationMs)
        }
    }

    private fun markPlayed() {
        viewModelScope.launch { playbackRepository.setPlayed(episodeId, isPlayed = true) }
    }

    /**
     * What the page has reported so far.
     *
     * @property state what the player is doing.
     * @property error why it would not play, or null.
     * @property positionMs where it is.
     * @property durationMs the video's length, or null until known.
     */
    private data class PlayerReport(
        val state: EmbeddedPlayerState = EmbeddedPlayerState.UNSTARTED,
        val error: EmbeddedPlayerError? = null,
        val positionMs: Long = 0L,
        val durationMs: Long? = null,
    )

    companion object {
        /** The navigation argument naming the episode. */
        const val EPISODE_ID_ARG = "episodeId"

        /** The navigation argument naming where to start, or [RESUME]. */
        const val START_MS_ARG = "startMs"

        /** The [START_MS_ARG] value that means "where the episode was left". */
        const val RESUME: Long = -1L

        /** How far the position may move before it is written again. */
        private const val PERSIST_EVERY_MS = 5_000L

        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

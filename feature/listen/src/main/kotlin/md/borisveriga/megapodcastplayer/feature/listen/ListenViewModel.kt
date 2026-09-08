package md.borisveriga.megapodcastplayer.feature.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow

/**
 * What the Listen screen shows.
 *
 * @property continueListening episodes started and not finished, newest first.
 * @property newEpisodes episodes that arrived since the user last looked at their show.
 * @property upNext the durable queue, after the episode playing.
 * @property nowPlaying which episode the player has loaded, and whether it is running, so a shelf
 *   card can show it.
 * @property isLoading true until the first database emission arrives; distinct from "there is
 *   nothing to listen to", which is a real and different answer.
 */
data class ListenUiState(
    val continueListening: List<EpisodeWithShow> = emptyList(),
    val newEpisodes: List<EpisodeWithShow> = emptyList(),
    val upNext: List<EpisodeWithShow> = emptyList(),
    val nowPlaying: NowPlaying = NowPlaying(),
    val isLoading: Boolean = true,
) {
    /** True when there is nothing on any shelf and nothing left to wait for. */
    val isEmpty: Boolean
        get() = !isLoading &&
            continueListening.isEmpty() &&
            newEpisodes.isEmpty() &&
            upNext.isEmpty()

    /**
     * Finds an episode anywhere on the shelves.
     *
     * One episode can be on two of them — half-listened-to and also queued — so this deliberately
     * takes the first match rather than caring which shelf the tap came from: the sheet is about
     * the episode, and the episode is the same one either way.
     *
     * @param episodeId the episode to find.
     * @return the entry, or null when the shelves have moved on since the tap.
     */
    fun episodeById(episodeId: String): EpisodeWithShow? =
        (continueListening + newEpisodes + upNext).firstOrNull { it.episode.id == episodeId }
}

/**
 * As much of the player as a shelf card needs.
 *
 * Three fields rather than the whole `PlaybackState`, for the same reason the show page narrows it:
 * the player re-emits twice a second while playing, and carrying that into this state would rebuild
 * three shelves on every tick.
 *
 * @property episodeId the episode the player has loaded, or null.
 * @property isPlaying whether audio is actually coming out.
 * @property isBuffering whether it is preparing.
 */
data class NowPlaying(
    val episodeId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

/**
 * Drives the Listen screen.
 *
 * The app had no answer to "what should I listen to now". It had a library, which is an inventory
 * of shows, so every session began show → scroll → tap — and the two things a session actually
 * wants, *carry on with what I was listening to* and *what arrived since yesterday*, had no home at
 * all. Every fact needed to answer both was already in the database and had never been asked for
 * across shows.
 *
 * Three shelves, in the order the question is usually asked: what you were in the middle of, what
 * is new, and what you already lined up.
 *
 * @property podcastRepository supplies the two derived shelves.
 * @property playbackRepository supplies the queue.
 * @property episodePlayer starts playback from an episode id.
 * @property connection read only, and only for which card is playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListenViewModel @Inject constructor(
    private val podcastRepository: PodcastRepository,
    private val playbackRepository: PlaybackRepository,
    private val episodePlayer: EpisodePlayer,
    private val connection: PlaybackConnection,
) : ViewModel() {

    /**
     * The episode whose sheet is open, or null.
     *
     * An id rather than the episode, so the sheet redraws from the shelves: a download that
     * finishes or a mark that lands while it is open reaches it without anything copying the row.
     */
    private val openEpisodeIdState = MutableStateFlow<String?>(null)

    /** Which card is playing; narrowed before the distinct check. See [NowPlaying]. */
    private val nowPlaying: Flow<NowPlaying> = connection.playbackState
        .map { playback ->
            NowPlaying(
                episodeId = playback.episodeId,
                isPlaying = playback.isPlaying,
                isBuffering = playback.isBuffering,
            )
        }
        .distinctUntilChanged()

    val uiState: StateFlow<ListenUiState> = combine(
        podcastRepository.observeInProgressEpisodes(SHELF_LIMIT),
        podcastRepository.observeNewEpisodes(SHELF_LIMIT),
        playbackRepository.observeQueue(),
        connection.playbackState.map { it.episodeId }.distinctUntilChanged(),
        nowPlaying,
    ) { inProgress, new, queue, loadedId, playing ->
        ListenUiState(
            continueListening = inProgress,
            newEpisodes = new,
            // The durable queue includes the episode playing — the service mirrors its whole
            // timeline into it — so "up next" is what follows that one, computed exactly as the
            // queue screen computes it. Before a `MediaController` has bound there is no loaded
            // episode, and then the whole queue is still ahead of the user.
            upNext = queue
                .indexOfFirst { it.episode.id == loadedId }
                .let { index -> if (index >= 0) queue.drop(index + 1) else queue }
                .map { EpisodeWithShow(it.episode, it.showTitle, it.showArtworkUrl) },
            nowPlaying = playing,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ListenUiState(),
    )

    /** The episode whose sheet is open, for the screen to render. */
    val openEpisodeId: StateFlow<String?> = openEpisodeIdState

    /**
     * Plays an episode, or pauses it when it is the one already playing.
     *
     * What a card's play button does; the same toggle the show page's rows carry, so a play button
     * means the same thing wherever it is.
     *
     * @param episodeId the episode.
     * @param onPlaying invoked when a *new* episode was started, so the caller can open the player.
     */
    fun togglePlay(episodeId: String, onPlaying: () -> Unit) {
        if (uiState.value.nowPlaying.episodeId == episodeId) {
            viewModelScope.launch { connection.togglePlayPause() }
            return
        }
        viewModelScope.launch {
            if (episodePlayer.play(episodeId)) onPlaying()
        }
    }

    /** Opens an episode's sheet — what a tap on a card does. */
    fun openEpisode(episodeId: String) {
        openEpisodeIdState.value = episodeId
    }

    /** Closes the episode sheet. */
    fun closeEpisode() {
        openEpisodeIdState.value = null
    }

    private companion object {
        /**
         * How many episodes a shelf holds.
         *
         * A shelf is read across rather than scrolled through: someone with forty half-finished
         * episodes wants the recent ones. The library and the show page are where a complete list
         * lives.
         */
        const val SHELF_LIMIT = 12

        /** Keeps the shelves warm across a rotation or a fold. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

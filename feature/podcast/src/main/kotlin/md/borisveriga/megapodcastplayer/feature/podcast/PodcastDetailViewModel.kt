package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.chapters.ChapterResolver
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import md.borisveriga.megapodcastplayer.core.model.EpisodeSort
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.ShowSettings

/**
 * State rendered by the podcast detail screen.
 *
 * @property podcast the show; null while loading or after it has been removed.
 * @property episodes its episodes, newest first.
 * @property isLoading true until the first database emission arrives.
 * @property isRefreshing true while a pull-to-refresh is re-fetching this show's feed; ends in a
 *   snackbar either way.
 * @property isAutoRefreshing true while the refresh that runs on entering the screen is in flight.
 *   Separate from [isRefreshing] because it renders as a thin progress line and says nothing when
 *   it finishes.
 * @property isRebuilding true while the episode list is being deleted and imported again. Its own
 *   flag rather than a third kind of refresh, because it is the one operation on this screen that
 *   destroys what the user is looking at, and it should say so while it runs.
 * @property message a one-off refresh outcome for the snackbar.
 * @property openEpisodeId the episode whose sheet is open, or null. Held as an id rather than as
 *   the episode itself so the sheet redraws from the list — a download that finishes or a mark that
 *   lands while the sheet is open reaches it without anything having to copy the row again.
 * @property chapters the open episode's chapters, once resolved.
 * @property isChaptersLoading true while they are being looked for. Distinct from "there are none":
 *   fetching the publisher's document takes a network round trip, and a chapter list that appeared
 *   a second after the sheet did would read as the app having changed its mind.
 * @property nowPlaying which of these episodes the player has loaded, and whether it is running.
 * @property settings what the user has decided about this show — which end of the list it starts
 *   at, which episodes it shows, and the four behaviours the settings sheet holds. Remembered per
 *   show rather than per screen: a serial is read from the beginning every time it is opened, not
 *   only the first time.
 * @property appSpeed the app-wide playback rate, which the settings sheet names on the chip that
 *   defers to it — an override is only a decision if the thing being overridden is visible.
 * @property appAutoDownload the app-wide auto-download answer, shown for the same reason.
 */
data class PodcastDetailUiState(
    val podcast: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAutoRefreshing: Boolean = false,
    val isRebuilding: Boolean = false,
    val message: PodcastDetailMessage? = null,
    val openEpisodeId: String? = null,
    val chapters: EpisodeChapters = EpisodeChapters(),
    val isChaptersLoading: Boolean = false,
    val nowPlaying: NowPlaying = NowPlaying(),
    val settings: ShowSettings = ShowSettings.DEFAULT,
    val appSpeed: Float = PlaybackSettings.DEFAULT_SPEED,
    val appAutoDownload: Boolean = false,
) {
    /** The episode the sheet is about, or null when it is closed or the episode has gone. */
    val openEpisode: Episode? get() = episodes.firstOrNull { it.id == openEpisodeId }
}

/**
 * As much of the player as an episode list needs.
 *
 * Three fields rather than the whole `PlaybackState`, and that is the point: the player re-emits
 * its state twice a second while playing, and carrying it into this screen's state would rebuild
 * and recompose a list of two hundred rows on every tick. These three change when the user does
 * something, which is exactly when the list should move.
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
 * The three preference sources the show page reads, combined into one so [combine] stays within
 * its typed arity.
 *
 * @property show this show's own settings.
 * @property playback the app-wide playback settings, read only for the rate.
 * @property downloads the app-wide download settings, read only for the auto-download answer.
 */
private data class ShowPreferences(
    val show: ShowSettings,
    val playback: PlaybackSettings,
    val downloads: DownloadSettings,
)

/** A one-off outcome to show the user. */
sealed interface PodcastDetailMessage {

    /**
     * A refresh completed.
     *
     * @property newEpisodeCount episodes discovered; zero is a perfectly good answer.
     */
    data class Refreshed(val newEpisodeCount: Int) : PodcastDetailMessage

    /**
     * A refresh failed.
     *
     * @property reason short explanation for the snackbar.
     */
    data class RefreshFailed(val reason: String) : PodcastDetailMessage

    /**
     * The episode list was deleted and imported again.
     *
     * Separate from [Refreshed] because the number means something different: a refresh reports
     * what it *found*, a rebuild reports how much of the show there now is — which is the one
     * figure that says whether the rebuild fixed anything.
     *
     * @property episodeCount episodes the feed yielded.
     */
    data class Rebuilt(val episodeCount: Int) : PodcastDetailMessage

    /**
     * A rebuild failed, leaving the existing list untouched.
     *
     * @property reason short explanation for the snackbar.
     */
    data class RebuildFailed(val reason: String) : PodcastDetailMessage

    /**
     * An episode could not be played.
     *
     * The only way this happens is the show being removed between the list rendering and the tap
     * landing, so the message says that rather than blaming the network.
     */
    data object EpisodeUnavailable : PodcastDetailMessage

    /**
     * An episode was put at the head of the queue.
     *
     * Worth confirming because the queue is on another screen: the swipe otherwise closes over a
     * row that looks exactly as it did, and nothing on this screen says where the episode went.
     *
     * @property title the episode's title.
     */
    data class QueuedNext(val title: String) : PodcastDetailMessage

    /**
     * An episode was added to the end of the queue.
     *
     * Distinct from [QueuedNext] because the two put it in different places, and "queued" with no
     * indication of where is the one thing worse than no message at all.
     *
     * @property title the episode's title.
     */
    data class Queued(val title: String) : PodcastDetailMessage

    /**
     * An episode was queued for download.
     *
     * Worth confirming because the download itself may not start for a while — "Wi-Fi only" is on
     * by default, so a tap on mobile data appears to do nothing at all.
     *
     * @property title the episode's title.
     * @property waitingForWifi whether the download is waiting for an unmetered network.
     */
    data class DownloadQueued(val title: String, val waitingForWifi: Boolean) :
        PodcastDetailMessage

    /**
     * A downloaded episode was removed from the device.
     *
     * @property title the episode's title.
     */
    data class DownloadRemoved(val title: String) : PodcastDetailMessage

    /**
     * An episode was marked played, or put back to unplayed.
     *
     * The one message on this screen that is offered back. Marking played is reversible and the
     * gesture that does it is a swipe, so it is the classic case for an undo — and under the
     * *Unplayed* filter the row it applies to disappears as the mark lands, which takes the obvious
     * way of reversing it (swipe again) with it.
     *
     * @property title the episode's title.
     * @property isPlayed what it was marked as, which decides the wording.
     */
    data class PlayedChanged(val title: String, val isPlayed: Boolean) : PodcastDetailMessage
}

/**
 * Drives the podcast detail screen.
 *
 * @property repository the single source of podcast truth.
 * @property episodePlayer starts playback and edits the queue from an episode id.
 * @property downloadRepository requests and removes downloads.
 * @property chapterResolver finds the open episode's chapters, from whichever source has them.
 * @property showSettings this show's own settings: sort, filter, speed, downloads, notifications.
 * @property playbackRepository read only for the app-wide rate the settings sheet compares against.
 * @property connection read only, and only for which row is playing.
 * @param savedStateHandle carries the `podcastId` navigation argument.
 */
@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val episodePlayer: EpisodePlayer,
    private val downloadRepository: DownloadRepository,
    private val chapterResolver: ChapterResolver,
    private val showSettings: ShowSettingsRepository,
    private val playbackRepository: PlaybackRepository,
    private val connection: PlaybackConnection,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The show being displayed, taken from the navigation route. */
    private val podcastId: String = checkNotNull(savedStateHandle[PODCAST_ID_ARG]) {
        "PodcastDetailViewModel requires a '$PODCAST_ID_ARG' navigation argument"
    }

    /**
     * An episode the route asked to open on arrival, or null.
     *
     * Set by a new-episode notification that named exactly one episode. It is opened once, from
     * [init], and not remembered: coming back to this show later should land on the list, not on a
     * sheet about an episode from a notification the user dismissed days ago.
     */
    private val arrivingEpisodeId: String? = savedStateHandle[EPISODE_ID_ARG]

    private val transientState = MutableStateFlow(TransientState())

    /**
     * The mark [undoPlayedChange] would reverse, or null.
     *
     * Held here rather than in [PodcastDetailUiState] so the state stays comparable data, which is
     * the same rule the queue's undo follows.
     */
    private var pendingPlayedUndo: PlayedUndo? = null

    /**
     * Which row is playing, and nothing else about the player.
     *
     * `distinctUntilChanged` after the narrowing rather than before it is what makes this cheap:
     * the position ticks twice a second, and without it every tick would rebuild the UI state and
     * recompose the whole episode list.
     */
    private val nowPlaying: Flow<NowPlaying> = connection.playbackState
        .map { playback ->
            NowPlaying(
                episodeId = playback.episodeId,
                isPlaying = playback.isPlaying,
                isBuffering = playback.isBuffering,
            )
        }
        .distinctUntilChanged()

    val uiState: StateFlow<PodcastDetailUiState> = combine(
        repository.observePodcast(podcastId),
        repository.observeEpisodes(podcastId),
        transientState,
        nowPlaying,
        combine(
            showSettings.observeSettings(podcastId),
            playbackRepository.observePlaybackSettings(),
            downloadRepository.observeDownloadSettings(),
            ::ShowPreferences,
        ),
    ) { podcast, episodes, transient, playing, preferences ->
        PodcastDetailUiState(
            podcast = podcast,
            episodes = episodes,
            isLoading = false,
            isRefreshing = transient.isRefreshing,
            isAutoRefreshing = transient.isAutoRefreshing,
            isRebuilding = transient.isRebuilding,
            message = transient.message,
            openEpisodeId = transient.openEpisodeId,
            chapters = transient.chapters,
            isChaptersLoading = transient.isChaptersLoading,
            nowPlaying = playing,
            settings = preferences.show,
            appSpeed = preferences.playback.speed,
            appAutoDownload = preferences.downloads.autoDownloadNewEpisodes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PodcastDetailUiState(),
    )

    init {
        // The episode the notification named, once the list it belongs to has arrived. Waiting for
        // the list rather than opening blind is what lets [openEpisode] refuse an episode the show
        // no longer has — a feed that dropped it between the refresh and the tap.
        arrivingEpisodeId?.let { episodeId ->
            viewModelScope.launch {
                uiState.first { it.episodes.any { episode -> episode.id == episodeId } }
                openEpisode(episodeId)
            }
        }

        // Opening the episode list is what "seeing" the new episodes means, so the badges clear
        // here rather than on scroll.
        //
        // Ordering against the automatic refresh matters and is deliberate: this runs first, so an
        // episode that arrives while the user is looking at the list keeps its badge and stands out
        // as the thing that just appeared. Clearing the flags afterwards would hide exactly the
        // episode the refresh was worth doing for.
        viewModelScope.launch { repository.markEpisodesSeen(podcastId) }
    }

    /**
     * Applies a change made in the show settings sheet.
     *
     * Takes the whole object rather than one field at a time because the sheet edits four unrelated
     * settings and a setter apiece would be four methods that all did the same thing.
     *
     * @param settings the show's new settings.
     */
    fun setShowSettings(settings: ShowSettings) {
        viewModelScope.launch { showSettings.update(podcastId) { settings } }
    }

    /**
     * Remembers which end of this show the list starts at.
     *
     * Per show, and stored, because it is a property of the show rather than of the visit: a course
     * or an audiobook is read from the beginning every time it is opened, and a control that reset
     * to newest-first on every entry would be a control the user has to use twice.
     *
     * @param sort the direction to list episodes in from now on.
     */
    fun setSort(sort: EpisodeSort) {
        viewModelScope.launch {
            showSettings.update(podcastId) { it.copy(episodeSort = sort) }
        }
    }

    /**
     * Remembers which episodes this show lists.
     *
     * The filter used to be screen state and reset to *All* on every visit, which made it useless
     * for the case it exists for: working through the downloaded episodes of one show over a week
     * of commutes meant re-picking *Downloaded* every morning.
     *
     * @param filter the chip the user picked.
     */
    fun setFilter(filter: EpisodeFilter) {
        viewModelScope.launch {
            showSettings.update(podcastId) { it.copy(episodeFilter = filter) }
        }
    }

    /**
     * Brings this show up to date on entering the screen, quietly.
     *
     * Unlike the library's equivalent this ignores the per-show background-refresh toggle. That
     * toggle declines *bulk* refreshes; opening the episode list is the user pointing at this show
     * in particular, and since there is no manual refresh button any more, honouring the toggle
     * here would leave an opted-out show with no way to ever update.
     *
     * Says nothing when it finishes, success or failure — pulling to refresh is what asks a
     * question and expects an answer.
     */
    fun refreshIfStale() {
        if (transientState.value.isBusy) return
        transientState.value = transientState.value.copy(isAutoRefreshing = true)
        viewModelScope.launch {
            repository.refresh(podcastId, staleAfter = AUTO_REFRESH_STALE_AFTER)
            transientState.value = transientState.value.copy(isAutoRefreshing = false)
        }
    }

    /**
     * Re-fetches this show's feed however recently it was last fetched, and reports what happened.
     *
     * Never downloads audio.
     */
    fun refresh() {
        if (transientState.value.isBusy) return
        transientState.value = TransientState(isRefreshing = true)
        viewModelScope.launch {
            val result = repository.refresh(podcastId)
            transientState.value = TransientState(
                isRefreshing = false,
                message = result.fold(
                    onSuccess = { discovered ->
                        // Zero covers both "the server said nothing changed" and "the feed changed
                        // but gained no episodes"; to the user those are the same answer.
                        PodcastDetailMessage.Refreshed(discovered)
                    },
                    onFailure = { error ->
                        PodcastDetailMessage.RefreshFailed(
                            error.message ?: error::class.simpleName.orEmpty(),
                        )
                    },
                ),
            )
        }
    }

    /**
     * Deletes this show's episode list and imports the feed again from scratch.
     *
     * The escape hatch for a list a refresh cannot repair — a feed re-issued under new GUIDs, a
     * playlist whose stored order has drifted, an import that only half-worked — where every
     * further refresh merges into the same wrong list. It costs playback progress, played flags
     * and any hand-made order, which is the trade the user is making by choosing it.
     *
     * It also clears the show's downloads. The rows that tracked them do not survive the rebuild,
     * so leaving the audio in place would strand however many gigabytes on the device with nothing
     * left pointing at them. The ids are read *before* the rebuild, because afterwards the rows
     * that named them are gone, and the removal runs only on success — a failed rebuild leaves the
     * old list in place, and those downloads still belong to it.
     */
    fun rebuild() {
        if (transientState.value.isBusy) return
        transientState.value = TransientState(isRebuilding = true)
        val downloadedIds = uiState.value.episodes
            .filter { it.downloadState != DownloadState.NOT_DOWNLOADED }
            .map { it.id }

        viewModelScope.launch {
            val result = repository.rebuild(podcastId)
            if (result.isSuccess) {
                downloadedIds.forEach { downloadRepository.removeDownload(it) }
            }
            transientState.value = TransientState(
                isRebuilding = false,
                message = result.fold(
                    onSuccess = { PodcastDetailMessage.Rebuilt(it) },
                    onFailure = { error ->
                        PodcastDetailMessage.RebuildFailed(
                            error.message ?: error::class.simpleName.orEmpty(),
                        )
                    },
                ),
            )
        }
    }

    /** Removes the show. The screen should navigate back once [PodcastDetailUiState.podcast] is null. */
    fun removePodcast() {
        viewModelScope.launch { repository.remove(podcastId) }
    }

    /**
     * Plays an episode, resuming from wherever it was left.
     *
     * @param episodeId the episode to play.
     * @param onPlaying invoked once playback has been handed to the player, so the caller can open
     *   the full player. Not called when the episode has gone.
     */
    fun playEpisode(episodeId: String, onPlaying: () -> Unit) {
        viewModelScope.launch {
            if (episodePlayer.play(episodeId)) {
                onPlaying()
            } else {
                transientState.value = transientState.value.copy(
                    message = PodcastDetailMessage.EpisodeUnavailable,
                )
            }
        }
    }

    /**
     * Puts an episode at the head of the queue, to play when the current one ends.
     *
     * Distinct from tapping the row, which interrupts whatever is playing. Both belong on this
     * screen for the same reason: a list of a show's episodes is where someone decides what to
     * listen to next, and until now the only thing it could do with that decision was act on it
     * immediately.
     *
     * Nothing is playing? Then the queue is empty, the episode lands in it alone, and it plays on
     * the next tap of play — which is what "next" means from an empty queue.
     *
     * @param episodeId the episode to queue.
     */
    fun playNext(episodeId: String) {
        // Read before the suspend, and from the state rather than from the player: it is the only
        // place the title is, and the episode may be gone from both by the time the queue answers.
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            transientState.value = transientState.value.copy(
                message = if (episodePlayer.playNext(episodeId)) {
                    PodcastDetailMessage.QueuedNext(episode.title)
                } else {
                    // The player refuses an episode it cannot resolve — one whose show was removed
                    // under the gesture. Saying it was queued would be a lie the queue contradicts.
                    PodcastDetailMessage.EpisodeUnavailable
                },
            )
        }
    }

    /**
     * Plays the episode, or pauses it when it is the one already playing.
     *
     * What the play button on a row does. The row's *tap* opens the sheet now, so this is the
     * gesture that kept playing at one tap — and because the button is a toggle it is also the
     * first place in a list where the episode running can be paused without opening the player.
     *
     * @param episodeId the episode the button belongs to.
     * @param onPlaying invoked when a *new* episode was started, so the caller can open the player.
     *   Not invoked for a pause or a resume of the loaded episode: the player is already showing it.
     */
    fun togglePlay(episodeId: String, onPlaying: () -> Unit) {
        if (uiState.value.nowPlaying.episodeId == episodeId) {
            viewModelScope.launch { connection.togglePlayPause() }
            return
        }
        playEpisode(episodeId, onPlaying)
    }

    /**
     * Opens the episode sheet.
     *
     * What a tap on a row does now. The tap used to play the episode, and there was nowhere at all
     * to *read* one: the show notes were parsed and stored from the first release and displayed on
     * no screen, and the chapters with them. Playing is still one tap — the play button on the
     * row — so nothing got slower; what got possible is finding out what an episode is first.
     *
     * Resolving the chapters is launched here rather than in the sheet, because it can reach the
     * network and a composable is the wrong place to own a request that outlives one frame.
     *
     * @param episodeId the episode to open.
     */
    fun openEpisode(episodeId: String) {
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return

        transientState.value = transientState.value.copy(
            openEpisodeId = episodeId,
            chapters = EpisodeChapters(),
            isChaptersLoading = true,
        )

        viewModelScope.launch {
            val resolved = chapterResolver.chaptersFor(episode)
            // Checked on arrival rather than assumed: a fetch takes a round trip, and the user may
            // have closed the sheet or opened a different episode in the meantime. Writing anyway
            // would put one episode's chapters under another episode's title.
            if (transientState.value.openEpisodeId != episodeId) return@launch
            transientState.value = transientState.value.copy(
                chapters = resolved,
                isChaptersLoading = false,
            )
        }
    }

    /** Closes the episode sheet, and forgets what it was showing. */
    fun closeEpisode() {
        transientState.value = transientState.value.copy(
            openEpisodeId = null,
            chapters = EpisodeChapters(),
            isChaptersLoading = false,
        )
    }

    /**
     * Plays an episode from a given position — what tapping a chapter does.
     *
     * Distinct from [playEpisode], which resumes: a chapter is a request for one particular second
     * of an episode, whether or not it has been heard before.
     *
     * @param episodeId the episode to play.
     * @param positionMs where to start.
     * @param onPlaying invoked once the player has it, so the caller can open the player.
     */
    fun playFrom(episodeId: String, positionMs: Long, onPlaying: () -> Unit) {
        viewModelScope.launch {
            if (episodePlayer.playFrom(episodeId, positionMs)) {
                onPlaying()
            } else {
                transientState.value = transientState.value.copy(
                    message = PodcastDetailMessage.EpisodeUnavailable,
                )
            }
        }
    }

    /**
     * Adds an episode to the end of the queue.
     *
     * The sheet's third playback verb, beside "play now" and "play next". A list row has room for
     * two of them and the sheet has room for all three, which is part of what the sheet is for.
     *
     * @param episodeId the episode to queue.
     */
    fun addToQueue(episodeId: String) {
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            transientState.value = transientState.value.copy(
                message = if (episodePlayer.addToQueue(episodeId)) {
                    PodcastDetailMessage.Queued(episode.title)
                } else {
                    PodcastDetailMessage.EpisodeUnavailable
                },
            )
        }
    }

    /**
     * Marks an episode played, or puts it back to unplayed.
     *
     * The second most common action in a list of episodes after playing one, and until now the only
     * place in the app that could do it at all was the player — which meant marking an episode
     * played required playing it. It is offered on the short-swipe tier beside *Play next*, and as a
     * named accessibility action, so a TalkBack user has it too.
     *
     * @param episodeId the episode to mark.
     * @param isPlayed true to mark it finished, false to put it back to the start.
     */
    fun setPlayed(episodeId: String, isPlayed: Boolean) {
        // Read before the suspend and from the state: it is the only place the title is, and under
        // a filter the episode leaves the list the moment the mark lands.
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return

        viewModelScope.launch {
            episodePlayer.setPlayed(episodeId, isPlayed)
            pendingPlayedUndo = PlayedUndo(episodeId = episodeId, wasPlayed = episode.isPlayed)
            transientState.value = transientState.value.copy(
                message = PodcastDetailMessage.PlayedChanged(episode.title, isPlayed),
            )
        }
    }

    /**
     * Reverses the last mark.
     *
     * Consumed rather than kept, for the same reason every other undo in the app is: one left armed
     * past the snackbar that offered it would fire on the next message instead.
     *
     * The position is *not* restored. `setPlayed` resets it by design — see [EpisodePlayer.setPlayed]
     * — and an undo that put back "played: no, position: 41 minutes" would restore a state the mark
     * never came from.
     */
    fun undoPlayedChange() {
        val undo = pendingPlayedUndo ?: return
        pendingPlayedUndo = null
        transientState.value = transientState.value.copy(message = null)

        viewModelScope.launch { episodePlayer.setPlayed(undo.episodeId, undo.wasPlayed) }
    }

    /**
     * Applies a completed reorder gesture on a hand-ordered show.
     *
     * The screen may be showing a filtered subset, so two positions in that subset name the wrong
     * episodes in the full list. The translation is to keep the *slots*: whichever positions the
     * visible episodes occupy in the full order stay theirs, and the visible episodes are dealt
     * back into them in their new sequence. Everything hidden stays exactly where it was, which is
     * the only behaviour that does not surprise someone who later clears the filter.
     *
     * @param visibleIds the episode ids on screen, in the order they were in before the drag.
     * @param from the episode's position among those, before the drag.
     * @param to where it was dropped.
     */
    fun moveEpisode(visibleIds: List<String>, from: Int, to: Int) {
        if (from !in visibleIds.indices || to !in visibleIds.indices || from == to) return

        val reordered = visibleIds.toMutableList().apply { add(to, removeAt(from)) }.iterator()
        val visible = visibleIds.toSet()
        val merged = uiState.value.episodes.map { episode ->
            if (episode.id in visible) reordered.next() else episode.id
        }

        viewModelScope.launch { repository.reorderEpisodes(podcastId, merged) }
    }

    /**
     * Downloads an episode, or removes it if it is already on the device.
     *
     * One handler for both because the row shows one button whose meaning depends on the episode's
     * state — which is how every podcast app behaves, and what saves a row from carrying two icons
     * that are each useful half the time.
     *
     * @param episodeId the episode to download or remove.
     */
    fun toggleDownload(episodeId: String) {
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            when (episode.downloadState) {
                // A failed download is retried rather than cleared: the user tapping the button
                // again plainly means "try that again".
                DownloadState.NOT_DOWNLOADED, DownloadState.FAILED -> {
                    if (downloadRepository.download(episodeId)) {
                        val waitingForWifi =
                            downloadRepository.observeDownloadSettings().first().unmeteredOnly
                        transientState.value = transientState.value.copy(
                            message = PodcastDetailMessage.DownloadQueued(
                                title = episode.title,
                                waitingForWifi = waitingForWifi,
                            ),
                        )
                    } else {
                        transientState.value = transientState.value.copy(
                            message = PodcastDetailMessage.EpisodeUnavailable,
                        )
                    }
                }

                // Tapping a download in progress cancels it; tapping a finished one frees it.
                DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.COMPLETED -> {
                    downloadRepository.removeDownload(episodeId)
                    transientState.value = transientState.value.copy(
                        message = PodcastDetailMessage.DownloadRemoved(episode.title),
                    )
                }
            }
        }
    }

    /** Clears the current message once its snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null)
        // The message and its undo go together: one left armed past the snackbar that offered it
        // would fire on whichever message came next.
        pendingPlayedUndo = null
    }

    /**
     * Everything needed to reverse one mark.
     *
     * @property episodeId the episode that was marked.
     * @property wasPlayed what it was before.
     */
    private data class PlayedUndo(
        val episodeId: String,
        val wasPlayed: Boolean,
    )

    private data class TransientState(
        val openEpisodeId: String? = null,
        val chapters: EpisodeChapters = EpisodeChapters(),
        val isChaptersLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val isAutoRefreshing: Boolean = false,
        val isRebuilding: Boolean = false,
        val message: PodcastDetailMessage? = null,
    ) {
        /**
         * Whether a feed operation of any kind is already running.
         *
         * One guard for all three, so the automatic refresh cannot start on top of a
         * pull-to-refresh and swallow the answer it was about to give, nor the reverse — and so
         * that neither refresh can land its episodes in a list a rebuild is halfway through
         * replacing.
         */
        val isBusy: Boolean get() = isRefreshing || isAutoRefreshing || isRebuilding
    }

    companion object {
        /** Name of the navigation argument carrying the podcast id. */
        const val PODCAST_ID_ARG = "podcastId"

        /**
         * The optional episode argument on the same route.
         *
         * Spelled as a string because that is how a `SavedStateHandle` is keyed; it has to match
         * the property name on `Route.PodcastDetail`, and nothing but a test can check that it does.
         */
        const val EPISODE_ID_ARG = "episodeId"

        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How stale this show's feed must be before opening it re-fetches it.
         *
         * Matches the library's window; the two run against the same feeds and disagreeing about
         * what counts as recent would only produce requests neither of them wanted.
         */
        private val AUTO_REFRESH_STALE_AFTER: Duration = Duration.ofMinutes(15)
    }
}

package md.borisveriga.megapodcastplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.chapters.ChapterResolver
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.MomentsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.SleepTimer
import md.borisveriga.megapodcastplayer.core.media.SleepTimerState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import md.borisveriga.megapodcastplayer.core.model.chapters.indexOfCurrent
import md.borisveriga.megapodcastplayer.core.model.chapters.nextStartAfter
import md.borisveriga.megapodcastplayer.core.model.chapters.previousStartBefore

/**
 * One chapter the sleep timer can be told to stop at the end of.
 *
 * @property index where the chapter sits in the episode's list, which is what identifies it: two
 *   chapters called "Ad break" are still two chapters.
 * @property title what the publisher called it.
 */
data class SleepChapterOption(val index: Int, val title: String)

/**
 * State rendered by the mini player, the now-playing screen and the queue.
 *
 * @property playback what the player is doing right now.
 * @property settings the user's speed and skip preferences.
 * @property queue the durable "up next" queue, in play order, including the episode playing.
 * @property lastPlayedEpisodeId the episode the player last loaded, as stored. Only read while
 *   [playback] has no episode of its own; see [currentEpisodeId].
 * @property message a one-off outcome for the queue screen's snackbar; cleared via
 *   [PlayerViewModel.onQueueMessageShown]. The player surfaces do not read it — they share this
 *   view model because they share the queue, not because they share every field of it.
 * @property download the offline copy of the episode named by [currentEpisodeId], or null while
 *   there is no episode to have one. Null is what hides the download button rather than showing it
 *   disabled: a control for an episode that does not exist has nothing to say.
 * @property sleep what the sleep timer is doing, if anything. It has absorbed the end-of-episode
 *   bell, which is now one of its options rather than a second control beside it.
 * @property moments the moments already saved in the episode named by [currentEpisodeId], earliest
 *   first. Their count is drawn beside the mark button, so pressing it is visibly cumulative rather
 *   than a button that does nothing you can see; the list itself is what tapping that count opens.
 * @property momentSaved the moment a press just saved, for a snackbar that offers to put a note on
 *   it; cleared via [PlayerViewModel.onMomentMessageShown].
 * @property chapters the loaded episode's chapters, once resolved; empty when it has none.
 * @property dismissed true once the player has been put away and the snackbar offering it back has
 *   not been shown yet; cleared via [PlayerViewModel.onDismissMessageShown]. A flag rather than the
 *   queue it emptied, for the same reason [message] is: the UI state stays data a test can compare,
 *   and the payload lives with the view model that will replay it.
 */
data class PlayerUiState(
    val playback: PlaybackState = PlaybackState(),
    val settings: PlaybackSettings = PlaybackSettings(),
    val queue: List<PlayableEpisode> = emptyList(),
    val lastPlayedEpisodeId: String? = null,
    val message: QueueMessage? = null,
    val download: EpisodeDownload? = null,
    val sleep: SleepTimerState = SleepTimerState(),
    val moments: List<Moment> = emptyList(),
    val momentSaved: SavedMoment? = null,
    val chapters: List<Chapter> = emptyList(),
    val dismissed: Boolean = false,
) {

    /**
     * The chapter the playhead is inside, or null.
     *
     * Derived rather than stored: the position moves twice a second and the chapters do not, so
     * keeping an index in the state would rebuild it on every tick to say the same thing.
     */
    val currentChapter: Chapter?
        get() = chapters.getOrNull(chapters.indexOfCurrent(playback.positionMs))

    /**
     * Where the chapters start, as fractions of the episode — what the scrubber ticks.
     *
     * Empty while the duration is unknown: a fraction of an unknown total is not a position, and
     * ticks bunched at the left edge would be worse than none.
     */
    val chapterMarks: List<Float>
        get() {
            val duration = playback.knownDurationMs ?: return emptyList()
            return chapters.map { (it.startMs.toFloat() / duration).coerceIn(0f, 1f) }
        }

    /**
     * The artwork to show: the current chapter's, when it has one, else the episode's.
     *
     * Chapter artwork is the one piece of chapter data that changes what the *player* looks like
     * rather than what it says, and a publisher who attaches it means it to be seen.
     */
    val artworkUrl: String?
        get() = currentChapter?.imageUrl ?: playback.artworkUrl

    /** How many moments this episode has; what the mark button draws beside itself. */
    val momentCount: Int get() = moments.size

    /**
     * The chapters the sleep timer can stop at the end of: the one playing and every one after it.
     *
     * Not the ones already played, because "stop at the end of a chapter that has ended" has no
     * meaning that is not a surprise. A playhead before the first chapter — a list that opens at
     * something other than zero — is offered all of them, since every one is still to come. Empty
     * when the episode has no chapters, which is what keeps the option off the sheet.
     *
     * Computed here rather than in the timer, which knows nothing about chapters: the view model
     * is the only place that has both them and the playhead.
     */
    val sleepChapterOptions: List<SleepChapterOption>
        get() {
            val from = chapters.indexOfCurrent(playback.positionMs).coerceAtLeast(0)
            return chapters.drop(from).mapIndexed { offset, chapter ->
                SleepChapterOption(index = from + offset, title = chapter.title)
            }
        }

    /** True when there is nothing to show — the mini player should not be on screen at all. */
    val isIdle: Boolean get() = playback.isIdle

    /**
     * Whether what is playing is running at a rate the show asked for rather than the app's.
     *
     * Asked of the player rather than of storage, and that is the point: the per-show rate is
     * applied by `ShowSpeedApplier` wherever an episode starts, so the *player's* rate differing
     * from the app's is exactly the observable fact, whatever set it. A speed that changes between
     * shows with no visible reason reads as a bug, so the button says so.
     */
    val hasShowSpeed: Boolean
        get() = !playback.isIdle && abs(playback.speed - settings.speed) > SPEED_TOLERANCE

    /**
     * The episode the queue's head belongs to: what the player has loaded, or failing that what it
     * loaded last.
     *
     * The fallback is not cosmetic. The durable queue *includes* the episode playing — the service
     * mirrors its whole timeline into it — so telling that entry apart from the ones waiting behind
     * it is the only thing that keeps it out of "up next". The player's own answer is null for as
     * long as it takes a `MediaController` to bind, and null again after the service has been
     * killed, and in both of those windows the queue screen would otherwise list the loaded episode
     * as though it were queued: one row, in a queue the user has emptied.
     */
    val currentEpisodeId: String? get() = playback.episodeId ?: lastPlayedEpisodeId

    /**
     * The queue entry the player has loaded, or null.
     *
     * The queue screen's header, and nothing else: what is playing is the player's business
     * everywhere else in the app. Matched by [currentEpisodeId] rather than taken from the head of
     * the queue, because the head is only the current episode while the two are in step — and they
     * are not for as long as it takes a `MediaController` to bind.
     *
     * Null as well while that entry [isCurrentUnstarted]: it is then a row in [upNext], and drawing
     * it twice would be the queue claiming two episodes where it holds one.
     */
    val nowPlaying: PlayableEpisode?
        get() = loadedEntry.takeUnless { isCurrentUnstarted }

    /**
     * The queue entries waiting to play, which is what the queue screen lists and edits.
     *
     * Everything after the loaded episode — and the loaded episode itself while it
     * [isCurrentUnstarted]. Entries *before* it are left out on purpose: they have been played past,
     * and queueing one of them again moves it to the end rather than listing it here.
     */
    val upNext: List<PlayableEpisode>
        get() = queue.drop(if (isCurrentUnstarted) loadedIndex else loadedIndex + 1)

    /**
     * How many episodes follow the loaded one, which is what the player's "up next" link counts.
     *
     * Not `upNext.size`: the player is showing the loaded episode, started or not, and counting it
     * as "next" there would be counting the thing on screen as coming after itself.
     */
    val followingCount: Int get() = (queue.size - (loadedIndex + 1)).coerceAtLeast(0)

    /**
     * Whether the loaded episode has never been played at all.
     *
     * Queueing into a player that holds nothing makes that episode the player's current item —
     * there is no other place for a first item to go — so it arrives "loaded" without anyone having
     * asked to hear it. To the user it is an episode they queued, and the queue has to list it as
     * one: removable, counted in the time remaining, cleared by *Clear queue*. The moment it is
     * started it is what is playing, and becomes the header like any other.
     *
     * Both positions are asked, because each is blind where the other sees: the player's is zero
     * until a controller binds, and the stored one is up to five seconds behind a playing episode.
     * And only the player's own word counts for *which* episode: while [currentEpisodeId] is the
     * stored fallback the player has said nothing yet, and a row drawn on that guess would appear
     * and vanish again as the controller binds.
     */
    val isCurrentUnstarted: Boolean
        get() {
            val loaded = loadedEntry ?: return false
            if (playback.episodeId == null) return false
            return !playback.isPlaying && playback.positionMs == 0L && loaded.episode.positionMs == 0L
        }

    /** Where [currentEpisodeId] sits in [queue], or -1 when it is not queued. */
    private val loadedIndex: Int
        get() = queue.indexOfFirst { it.episode.id == currentEpisodeId }

    /** The queue entry for [currentEpisodeId], or null. */
    private val loadedEntry: PlayableEpisode? get() = queue.getOrNull(loadedIndex)
}

/**
 * As much of an episode's offline copy as the player draws.
 *
 * Narrower than the [md.borisveriga.megapodcastplayer.core.model.Episode] it is read from, and that
 * is the point: a running transfer writes its percentage to the database several times a second,
 * and carrying the whole episode into [PlayerUiState] would rebuild — and recompose — the entire
 * player sheet on every one of those writes. Two fields can be compared, so the sheet only moves
 * when what it shows moves.
 *
 * @property state the five-way download state the button draws a face for.
 * @property percent progress in `0f..100f`; only meaningful while [state] is
 *   [DownloadState.DOWNLOADING].
 */
data class EpisodeDownload(
    val state: DownloadState,
    val percent: Float,
)

/**
 * A moment that has just been saved, waiting to be acknowledged.
 *
 * Modelled as state rather than an event for the same reason [QueueMessage] is: it survives a fold
 * or a rotation, and it keeps the UI state something a test can compare. It carries the row id
 * because the snackbar's action writes a note onto *this* moment, and the position because the
 * message names the second that was marked — without which two presses a minute apart produce two
 * identical messages.
 *
 * @property id the saved moment's row id.
 * @property positionMs where it was marked.
 */
data class SavedMoment(
    val id: Long,
    val positionMs: Long,
)

/**
 * Something a queue gesture did, to be shown once in a snackbar.
 *
 * Modelled as state rather than an event channel so it survives configuration changes and the
 * unfold/fold transition on the Fold 7. It is reversible, and it says so: the message names what
 * happened, and [PlayerViewModel.undoQueueChange] is what puts it back. The undo payload itself is
 * not here — it is the view model's, so that the UI state stays data a test can compare.
 *
 * @property episodeTitle the affected episode, named back to the user so a snackbar arriving after
 *   two quick swipes is not ambiguous.
 */
sealed interface QueueMessage {

    /** An episode was taken out of the queue by a full swipe. */
    data class Removed(val episodeTitle: String) : QueueMessage

    /**
     * The whole "up next" list was emptied at once.
     *
     * Carries the count rather than a title: naming one of eleven episodes would be arbitrary, and
     * the number is the fact worth confirming — it is the difference between clearing a queue of
     * two and losing an evening's planning.
     *
     * @property count how many episodes left the queue.
     */
    data class Cleared(val count: Int) : QueueMessage
}

/**
 * Drives both the mini player and the full now-playing screen.
 *
 * Both surfaces show the same player, so they share one view model rather than two that would have
 * to be kept in step. Nothing here holds playback state of its own: the single source of truth is
 * the [PlaybackConnection], which reflects the service.
 *
 * @property connection the handle on the playback service.
 * @property playbackRepository the durable queue and playback preferences.
 * @property episodePlayer resolves episode ids into something the player can accept.
 * @property podcastRepository read only, and only for the current episode's download state.
 * @property downloadRepository starts and removes the current episode's offline copy.
 * @property momentsRepository saves the marks the user makes while listening.
 * @property sleepTimer stops playback after a while; it owns the end-of-episode bell too.
 * @property chapterResolver finds the loaded episode's chapters, from whichever source has them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    private val episodePlayer: EpisodePlayer,
    private val podcastRepository: PodcastRepository,
    private val downloadRepository: DownloadRepository,
    private val momentsRepository: MomentsRepository,
    private val sleepTimer: SleepTimer,
    private val chapterResolver: ChapterResolver,
) : ViewModel() {

    /**
     * The undo the last queue gesture left behind, or null.
     *
     * Held here rather than in [PlayerUiState] so the state stays comparable data. Overwritten
     * rather than stacked: a snackbar shows one message at a time, so only the newest gesture is
     * ever reachable, and keeping the older ones would only let an undo fire for a message that has
     * already gone.
     */
    private var pendingUndo: QueueUndo? = null

    private val messageState = MutableStateFlow<QueueMessage?>(null)

    private val momentSavedState = MutableStateFlow<SavedMoment?>(null)

    private val dismissedState = MutableStateFlow(false)

    /**
     * The queue [dismiss] emptied, kept so [undoDismiss] can put it back.
     *
     * Held here rather than in [PlayerUiState] for the same reason [pendingUndo] is, and cleared
     * with the snackbar that offered it: an undo left armed past its message would restore a queue
     * the user has since replaced.
     */
    private var pendingDismissal: DismissedPlayback? = null

    /**
     * Everything the player reads straight off the service, the durable queue and the bell.
     *
     * Split from [uiState] because `combine` takes at most five flows and the player now has six
     * sources. The sixth — the current episode's download state — is not merely one more: it has to
     * be looked up *from* the others, since which episode to observe is whatever this says is
     * playing.
     */
    private val core: Flow<PlayerCore> = combine(
        connection.playbackState,
        playbackRepository.observePlaybackSettings(),
        playbackRepository.observeQueue(),
        playbackRepository.observeLastPlayedEpisodeId(),
        sleepTimer.state,
    ) { playback, settings, queue, lastPlayedEpisodeId, sleep ->
        PlayerCore(
            playback = playback,
            settings = settings,
            queue = queue,
            lastPlayedEpisodeId = lastPlayedEpisodeId,
            sleep = sleep,
        )
    }

    /**
     * The current episode's offline copy, followed as the current episode changes.
     *
     * `distinctUntilChanged` appears twice and both are load-bearing. The first stops a new
     * database subscription being opened every time the position ticks; the second stops a
     * download's several-writes-a-second progress from rebuilding the whole UI state when the
     * rounded percentage has not moved.
     */
    private val currentDownload: Flow<EpisodeDownload?> = core
        .map { it.playback.episodeId ?: it.lastPlayedEpisodeId }
        .distinctUntilChanged()
        .flatMapLatest { episodeId ->
            episodeId?.let(podcastRepository::observeEpisode) ?: flowOf(null)
        }
        .map { episode ->
            episode?.let { EpisodeDownload(it.downloadState, it.downloadPercent) }
        }
        .distinctUntilChanged()

    /**
     * The loaded episode's chapters, followed as that episode changes.
     *
     * `flatMapLatest` over the episode id rather than over the state, for the same reason
     * [currentDownload] does it: without the `distinctUntilChanged` in front, every position tick
     * would start a fresh resolution — and a resolution can mean a network fetch.
     */
    private val chapters: Flow<List<Chapter>> = core
        .map { it.playback.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { episodeId ->
            if (episodeId == null) {
                flowOf(emptyList())
            } else {
                // The episode row, then its chapters: the resolver needs the description and the
                // stored list, which only the row has — narrowed to those fields before the
                // distinct check below.
                // The row is rewritten several times a second while playing — that is the
                // position ticking — and resolving on each of those could mean a network fetch a
                // second. Comparing `chaptersJson` alone is not enough: a null episode and an
                // episode with no stored list look identical through it, which drops the first
                // real emission.
                podcastRepository.observeEpisode(episodeId)
                    .distinctUntilChangedBy(Episode?::chapterInputs)
                    .map { episode ->
                        episode?.let { chapterResolver.chaptersFor(it).chapters }.orEmpty()
                    }
            }
        }

    /**
     * The current episode's moments, followed as that episode changes.
     *
     * The list rather than the count, now that the count is a way *into* the list: two queries for
     * the same rows would be one more thing to keep in step for no gain, and `size` is the count.
     *
     * `distinctUntilChanged` for the same reason it guards [currentDownload]: without it, every
     * position tick would open a fresh database subscription.
     */
    private val moments: Flow<List<Moment>> = core
        .map { it.playback.episodeId ?: it.lastPlayedEpisodeId }
        .distinctUntilChanged()
        .flatMapLatest(momentsRepository::observeForEpisode)

    val uiState: StateFlow<PlayerUiState> = combine(
        core,
        currentDownload,
        messageState,
        moments,
        combine(momentSavedState, dismissedState, chapters, ::Triple),
    ) { core, download, message, episodeMoments, (momentSaved, dismissed, episodeChapters) ->
        PlayerUiState(
            playback = core.playback,
            settings = core.settings,
            queue = core.queue,
            lastPlayedEpisodeId = core.lastPlayedEpisodeId,
            message = message,
            download = download,
            sleep = core.sleep,
            moments = episodeMoments,
            momentSaved = momentSaved,
            chapters = episodeChapters,
            dismissed = dismissed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlayerUiState(),
    )

    init {
        // A cold start finds an empty player; put the user's queue back so the mini player shows
        // what they were listening to. Idempotent, so several screens asking costs nothing.
        viewModelScope.launch { episodePlayer.restoreQueue() }
    }

    /** Starts or pauses playback. */
    fun togglePlayPause() {
        viewModelScope.launch { connection.togglePlayPause() }
    }

    /**
     * Seeks within the current episode.
     *
     * @param positionMs the absolute position to seek to.
     */
    fun seekTo(positionMs: Long) {
        viewModelScope.launch { connection.seekTo(positionMs) }
    }

    /** Jumps forward by the user's configured interval. */
    fun skipForward() {
        viewModelScope.launch { connection.skipForward(uiState.value.settings.skipForwardMs) }
    }

    /** Jumps back by the user's configured interval. */
    fun skipBack() {
        viewModelScope.launch { connection.skipBack(uiState.value.settings.skipBackMs) }
    }

    /**
     * Moves to the next chapter, or to the next queued episode when there are none.
     *
     * The same button, meaning the thing the user would mean by it. An episode with chapters is a
     * list of segments and *next* is the next segment; an episode without them is a single thing
     * and *next* is the next episode. The button's label says which, so nothing is ambiguous — see
     * the player's transport row.
     */
    fun skipToNext() {
        val state = uiState.value
        val nextChapter = state.chapters.nextStartAfter(state.playback.positionMs)
        viewModelScope.launch {
            if (nextChapter != null) connection.seekTo(nextChapter) else connection.skipToNext()
        }
    }

    /**
     * Moves to the previous chapter, or restarts / moves to the previous episode without chapters.
     *
     * `previousStartBefore` restarts the current chapter when the playhead is a little way into it,
     * which is the same "restart before you go back" behaviour the episode button has, one level
     * down.
     */
    fun skipToPrevious() {
        val state = uiState.value
        val previousChapter = state.chapters.previousStartBefore(state.playback.positionMs)
        viewModelScope.launch {
            if (previousChapter != null) {
                connection.seekTo(previousChapter)
            } else {
                connection.skipToPrevious()
            }
        }
    }

    /**
     * Applies a playback rate to the running player without remembering it.
     *
     * What the speed sheet calls while a thumb is moving. The rate has to reach the player on every
     * change — choosing a speed is done by ear, and a slider that only takes effect on release is a
     * guess — but a preference written thirty times per drag is thirty disk writes for one gesture.
     *
     * @param speed the rate to play at; clamped by the player.
     */
    fun previewSpeed(speed: Float) {
        viewModelScope.launch { connection.setSpeed(speed) }
    }

    /**
     * Applies a playback rate and remembers it.
     *
     * The preference is written as well as applied so the speed survives the service being killed.
     *
     * @param speed the rate to play at; clamped to [PlaybackSettings.SPEED_RANGE] on the way to
     *   storage.
     */
    fun setSpeed(speed: Float) {
        viewModelScope.launch {
            playbackRepository.setSpeed(speed)
            connection.setSpeed(speed)
        }
    }

    /** Plays a queued episode immediately. */
    fun playQueued(episodeId: String) {
        viewModelScope.launch { episodePlayer.play(episodeId) }
    }

    /**
     * Removes an episode from the queue, in the player and in storage, and offers it back.
     *
     * What a full right-to-left swipe on a queue row commits, and what the row's "remove"
     * accessibility action does. The queue is captured *before* the removal, because that
     * arrangement is the only description of where the episode belongs that survives it.
     *
     * @param episodeId the episode to drop.
     */
    fun removeFromQueue(episodeId: String) {
        val entry = uiState.value.queue.firstOrNull { it.episode.id == episodeId } ?: return
        val orderedIds = uiState.value.queue.map { it.episode.id }

        viewModelScope.launch {
            episodePlayer.removeFromQueue(episodeId)
            pendingUndo = QueueUndo(episodeIds = listOf(episodeId), orderedIds = orderedIds)
            messageState.value = QueueMessage.Removed(entry.episode.title)
        }
    }

    /**
     * Empties the queue of everything after the episode playing.
     *
     * The episode loaded is left alone: this is *Clear queue*, not *Stop*, and the two have
     * separate controls because they are separate decisions — the player's own dismiss is the one
     * that stops playback.
     *
     * Offered back rather than confirmed first, which is this app's rule for anything reversible:
     * clearing eleven episodes is undone by one tap on the snackbar, and a dialog in front of the
     * button would tax the ten times it was meant.
     */
    fun clearQueue() {
        val state = uiState.value
        val upNextIds = state.upNext.map { it.episode.id }
        if (upNextIds.isEmpty()) return

        // The whole queue, including the episode playing: it is the arrangement the removed
        // entries have to be inserted back into, and their indices are relative to it.
        val orderedIds = state.queue.map { it.episode.id }

        viewModelScope.launch {
            episodePlayer.clearFromQueue(upNextIds)
            pendingUndo = QueueUndo(episodeIds = upNextIds, orderedIds = orderedIds)
            messageState.value = QueueMessage.Cleared(upNextIds.size)
        }
    }

    /**
     * Reverses the last queue gesture.
     *
     * Consumed rather than kept: an undo that could be tapped twice would insert the episode once
     * and then attempt it again against a queue that already holds it.
     */
    fun undoQueueChange() {
        val undo = pendingUndo ?: return
        pendingUndo = null
        messageState.value = null

        viewModelScope.launch { episodePlayer.restoreAllToQueue(undo.episodeIds, undo.orderedIds) }
    }

    /** Clears the current [PlayerUiState.message] once its snackbar has been shown. */
    fun onQueueMessageShown() {
        messageState.value = null
        // The message and its undo go together: an undo left armed past the snackbar that offered
        // it would fire on the *next* one, restoring an episode the user never asked about.
        pendingUndo = null
    }

    /**
     * Applies a drag-to-reorder of the "up next" list.
     *
     * Both arguments are positions in [PlayerUiState.upNext] — what the queue screen actually
     * draws — and both are translated to the player's own indices here rather than in the
     * composable. That translation is the whole reason this method exists: `upNext` is the queue
     * *after* the episode playing, so a list index is never a player index, and handing a player a
     * `LazyColumn` index would reorder an episode the user never touched. Matching by id also means
     * a queue that has drifted out of step with the player does nothing rather than something
     * wrong.
     *
     * @param fromIndex the dragged episode's position in `upNext`.
     * @param toIndex the position it was dropped on.
     */
    fun moveInUpNext(fromIndex: Int, toIndex: Int) {
        val state = uiState.value
        val upNext = state.upNext
        val movedId = upNext.getOrNull(fromIndex)?.episode?.id ?: return
        val targetId = upNext.getOrNull(toIndex)?.episode?.id ?: return

        val playerQueue = state.playback.queueEpisodeIds
        val playerFrom = playerQueue.indexOf(movedId)
        val playerTo = playerQueue.indexOf(targetId)
        if (playerFrom < 0 || playerTo < 0) return

        val orderedIds = state.queue.map { it.episode.id }.movedTo(movedId, targetId) ?: return

        viewModelScope.launch { episodePlayer.moveInQueue(playerFrom, playerTo, orderedIds) }
    }

    /** Marks the current episode played, which also drops it from the queue and skips on. */
    fun markCurrentPlayed() {
        val episodeId = uiState.value.playback.episodeId ?: return
        // Through the player rather than straight to the repository, so this and the same action on
        // a list row are one behaviour: mark it, and take it out of the queue.
        viewModelScope.launch { episodePlayer.setPlayed(episodeId, isPlayed = true) }
    }

    /**
     * Starts, cancels, retries or deletes the current episode's offline copy.
     *
     * One control with one handler, branching on what the episode's state makes the tap *mean* —
     * the same mapping `PodcastDetailViewModel.toggleDownload` uses, so the identical button on the
     * two screens does the identical thing. `download` is safe to call for an episode that is
     * already downloading and for one that previously failed, which is what lets "start" and
     * "retry" be the same branch.
     */
    fun toggleCurrentDownload() {
        val state = uiState.value
        val episodeId = state.currentEpisodeId ?: return
        val download = state.download ?: return

        viewModelScope.launch {
            when (download.state) {
                DownloadState.NOT_DOWNLOADED, DownloadState.FAILED ->
                    downloadRepository.download(episodeId)

                DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.COMPLETED ->
                    downloadRepository.removeDownload(episodeId)
            }
        }
    }

    /**
     * Saves a moment at the playhead.
     *
     * The position comes from the player rather than from what the sheet last drew: the scrubber
     * moves on a 500 ms tick, and the whole promise of the button is that it marks the second the
     * user heard, not the second the screen last painted.
     *
     * A press with nothing loaded does nothing, as does one for an episode the database no longer
     * holds — the snackbar only appears when a moment actually exists to put a note on.
     */
    fun markMoment() {
        val state = uiState.value
        val episodeId = state.playback.episodeId ?: return
        val positionMs = state.playback.positionMs

        viewModelScope.launch {
            val moment = momentsRepository.mark(episodeId, positionMs) ?: return@launch
            // The moment's own position, not the one just pressed: a second press folded into an
            // existing mark must confirm the second that was kept, not the one it was discarded for.
            momentSavedState.value = SavedMoment(id = moment.id, positionMs = moment.positionMs)
        }
    }

    /**
     * Writes a note onto a moment the user has just saved.
     *
     * @param id the moment, as carried by [SavedMoment].
     * @param note what the user typed; blank clears it.
     */
    fun setMomentNote(id: Long, note: String) {
        viewModelScope.launch { momentsRepository.setNote(id, note) }
    }

    /** Clears [PlayerUiState.momentSaved] once its snackbar has been shown. */
    fun onMomentMessageShown() {
        momentSavedState.value = null
    }

    /**
     * Stops playback in [durationMs] milliseconds, fading out first.
     *
     * @param durationMs how long from now; a non-positive value cancels instead.
     */
    fun armSleepTimer(durationMs: Long) {
        sleepTimer.armAfter(durationMs)
    }

    /** Stops playback — and rings — when the episode playing finishes. */
    fun armSleepAtEndOfEpisode() {
        sleepTimer.armEndOfEpisode()
    }

    /**
     * Stops playback at the end of a chosen chapter of the episode playing, fading out first.
     *
     * Where the chapter ends is where the next one starts. The last chapter has no next one and
     * ends with the episode, which the timer is told by being given no position at all.
     *
     * @param index the chapter, as [SleepChapterOption.index] named it. Ignored when it names no
     *   chapter or nothing is loaded — a tap that raced the episode changing under the sheet.
     */
    fun armSleepAtEndOfChapter(index: Int) {
        val state = uiState.value
        val episodeId = state.playback.episodeId ?: return
        if (index !in state.chapters.indices) return

        sleepTimer.armEndOfChapter(
            chapterIndex = index,
            episodeId = episodeId,
            stopAtMs = state.chapters.getOrNull(index + 1)?.startMs,
        )
    }

    /**
     * Adds time to a running sleep timer.
     *
     * What a shake means: "I am still awake". Ignored when nothing is counting down.
     */
    fun extendSleepTimer() {
        sleepTimer.extend(SLEEP_EXTEND_MS)
    }

    /** Calls the sleep timer off; the player keeps going. */
    fun cancelSleepTimer() {
        sleepTimer.cancel()
    }

    /**
     * Stops playback and puts the player away.
     *
     * What a downward pull on the collapsed bar commits to, and what its spoken action does. This
     * is the only gesture in the app that ends a listening session, and it empties the queue to do
     * it — so the queue and the position are captured first and offered straight back through
     * [undoDismiss], which is the rule every other destructive-but-reversible action here follows.
     */
    fun dismiss() {
        val state = uiState.value
        if (state.isIdle && state.queue.isEmpty()) return

        pendingDismissal = DismissedPlayback(
            orderedIds = state.queue.map { it.episode.id },
            startEpisodeId = state.currentEpisodeId,
            positionMs = state.playback.positionMs,
        )

        viewModelScope.launch {
            episodePlayer.dismiss()
            dismissedState.value = true
        }
    }

    /**
     * Puts back the queue [dismiss] emptied, paused where it stopped.
     *
     * Consumed rather than kept, for the same reason [undoQueueChange] is.
     */
    fun undoDismiss() {
        val dismissal = pendingDismissal ?: return
        pendingDismissal = null
        dismissedState.value = false

        viewModelScope.launch {
            episodePlayer.restoreDismissed(
                orderedIds = dismissal.orderedIds,
                startEpisodeId = dismissal.startEpisodeId,
                positionMs = dismissal.positionMs,
            )
        }
    }

    /** Clears [PlayerUiState.dismissed] once its snackbar has been shown. */
    fun onDismissMessageShown() {
        dismissedState.value = false
        // The message and its undo go together; see [onQueueMessageShown].
        pendingDismissal = null
    }

    /** Clears the playback error once its snackbar has been shown. */
    fun onErrorShown() {
        connection.clearError()
    }

    /**
     * The five sources that can be combined directly, before the download state is looked up.
     *
     * Private and internal to the pipeline: it exists only because `combine` stops at five
     * arguments, and it is never exposed.
     *
     * @property playback what the player is doing right now.
     * @property settings the user's speed and skip preferences.
     * @property queue the durable queue, in play order.
     * @property lastPlayedEpisodeId the episode the player last loaded, as stored.
     * @property sleep what the sleep timer is doing.
     */
    private data class PlayerCore(
        val playback: PlaybackState,
        val settings: PlaybackSettings,
        val queue: List<PlayableEpisode>,
        val lastPlayedEpisodeId: String?,
        val sleep: SleepTimerState,
    )

    /**
     * Everything needed to reverse one queue gesture.
     *
     * A list rather than a single id, because one gesture can now remove eleven episodes: a swipe
     * puts one in it, *Clear queue* puts the whole of "up next" in it, and both are undone the same
     * way.
     *
     * @property episodeIds the episodes that left the queue, in queue order.
     * @property orderedIds the queue as it stood before they did, which is where they go back.
     */
    private data class QueueUndo(
        val episodeIds: List<String>,
        val orderedIds: List<String>,
    )

    /**
     * Everything needed to reverse a dismissal.
     *
     * @property orderedIds the queue as it stood, first to play first.
     * @property startEpisodeId the episode that was loaded.
     * @property positionMs how far into it playback had reached.
     */
    private data class DismissedPlayback(
        val orderedIds: List<String>,
        val startEpisodeId: String?,
        val positionMs: Long,
    )

    private companion object {
        /** Keeps the controller attached across a rotation or a fold. */
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How much a shake adds to the sleep timer.
         *
         * Fifteen minutes: long enough that someone who was nearly asleep does not have to shake
         * the phone again in two minutes, short enough that a shake in a pocket costs nothing.
         */
        const val SLEEP_EXTEND_MS = 15 * 60_000L
    }
}

/**
 * Everything about an episode that could change its chapters.
 *
 * Extracted so the flow above can ask "has anything the resolver reads moved?" without asking "has
 * the row changed?", which it does constantly.
 *
 * @property episodeId which episode it is; a different episode is always different chapters.
 * @property chaptersJson the stored list.
 * @property chaptersUrl the publisher's document.
 * @property description where a timestamp block would be read from.
 * @property durationMs used to reject description timestamps past the end.
 */
private data class ChapterInputs(
    val episodeId: String?,
    val chaptersJson: String?,
    val chaptersUrl: String?,
    val description: String,
    val durationMs: Long?,
)

/** This episode's chapter inputs; a null episode has none of them. */
private fun Episode?.chapterInputs(): ChapterInputs = ChapterInputs(
    episodeId = this?.id,
    chaptersJson = this?.chaptersJson,
    chaptersUrl = this?.chaptersUrl,
    description = this?.description.orEmpty(),
    durationMs = this?.durationMs,
)

/**
 * How far two rates may differ and still be the same setting.
 *
 * Rates make a round trip through a preference file and through Media3's own float, so exact
 * equality would light the "this show has its own speed" badge on rounding alone.
 */
private const val SPEED_TOLERANCE = 0.001f

package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.chapters.ChapterResolver
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode

/**
 * The episode sheet, for a screen that has no view model of its own for it.
 *
 * The show page owns its sheet's state, because the sheet is about a row in a list it is already
 * observing. Every other surface that wants to open an episode — the Listen shelves, and whatever
 * comes after them — has only an id, so this is the self-contained version: give it an episode id
 * and it reads the episode, resolves the chapters and wires every action.
 *
 * There is one [EpisodeSheet] and it is shared. An episode opened from a shelf and the same episode
 * opened from its own show have to be the same thing, or the app ends up with two answers to "what
 * is this episode" that drift the way the five hand-written episode rows did before
 * [md.borisveriga.megapodcastplayer.core.designsystem.component.EpisodeRow] existed.
 *
 * @param episodeId the episode to show.
 * @param showTitle the owning show's name, which the caller already has.
 * @param artworkUrl the episode's artwork or the show's, likewise.
 * @param onPlaying invoked once playback has started, so the caller can open the player.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt, keyed on [episodeId] so two episodes never share one.
 */
@Composable
fun EpisodeDetailSheet(
    episodeId: String,
    showTitle: String,
    artworkUrl: String?,
    onPlaying: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EpisodeSheetViewModel = hiltViewModel(key = episodeId),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.episode_share_title)

    LaunchedEffect(episodeId) { viewModel.load(episodeId) }

    val episode = uiState.episode ?: return

    EpisodeSheet(
        episode = episode,
        showTitle = showTitle,
        artworkUrl = artworkUrl,
        chapters = uiState.chapters,
        isChaptersLoading = uiState.isChaptersLoading,
        now = uiState.now,
        onPlay = {
            onDismiss()
            onPlaying()
        },
        onPlayChapter = { chapter ->
            onDismiss()
            viewModel.playFrom(chapter.startMs, onPlaying)
        },
        onPlayNext = {
            onDismiss()
            viewModel.playNext()
        },
        onAddToQueue = {
            onDismiss()
            viewModel.addToQueue()
        },
        // The two that leave the sheet open, because both change what the sheet itself shows.
        onToggleDownload = viewModel::toggleDownload,
        onSetPlayed = viewModel::setPlayed,
        onShare = {
            context.shareEpisode(
                episode = episode,
                showTitle = showTitle,
                chooserTitle = shareTitle,
            )
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/**
 * What the standalone episode sheet draws.
 *
 * @property episode the episode, or null until the database answers.
 * @property chapters its chapters, once resolved.
 * @property isChaptersLoading true while they are being looked for.
 * @property now the reference point for the published date, fixed when the sheet opened so the
 *   relative date does not change under the user while they read.
 */
data class EpisodeSheetUiState(
    val episode: Episode? = null,
    val chapters: EpisodeChapters = EpisodeChapters(),
    val isChaptersLoading: Boolean = false,
    val now: Instant = Instant.EPOCH,
)

/**
 * Drives [EpisodeDetailSheet].
 *
 * Keyed on the episode id by its caller rather than reading a navigation argument, because the
 * sheet is not a destination — it is a detour, and putting it on the back stack would make the
 * system back gesture mean "close the sheet" on one screen and "leave the screen" on the next.
 *
 * @property repository reads the episode, and keeps reading it: a download that finishes while the
 *   sheet is open changes what its chips say.
 * @property episodePlayer starts playback and edits the queue.
 * @property downloadRepository requests and removes the offline copy.
 * @property chapterResolver finds the chapters, from whichever source has them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EpisodeSheetViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val episodePlayer: EpisodePlayer,
    private val downloadRepository: DownloadRepository,
    private val chapterResolver: ChapterResolver,
) : ViewModel() {

    private val episodeIdState = MutableStateFlow<String?>(null)
    private val chaptersState = MutableStateFlow(EpisodeChapters())
    private val chaptersLoadingState = MutableStateFlow(false)

    /** Fixed when the sheet opens, so "2 days ago" does not become "3 days ago" mid-read. */
    private val openedAt = Instant.now()

    val uiState: StateFlow<EpisodeSheetUiState> = combine(
        episodeIdState.flatMapLatest { id ->
            id?.let(repository::observeEpisode) ?: flowOf(null)
        },
        chaptersState,
        chaptersLoadingState,
    ) { episode, chapters, isLoading ->
        EpisodeSheetUiState(
            episode = episode,
            chapters = chapters,
            isChaptersLoading = isLoading,
            now = openedAt,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = EpisodeSheetUiState(now = openedAt),
    )

    /**
     * Points the sheet at an episode, and starts looking for its chapters.
     *
     * Idempotent: a recomposition that calls it again with the same id changes nothing and does not
     * re-run a fetch that may have reached the network.
     *
     * @param episodeId the episode to show.
     */
    fun load(episodeId: String) {
        if (episodeIdState.value == episodeId) return
        episodeIdState.value = episodeId
        chaptersState.value = EpisodeChapters()
        chaptersLoadingState.value = true

        viewModelScope.launch {
            // The first non-null read only: the row is rewritten several times a second while the
            // episode plays, and the chapters do not change with the playhead.
            val episode = uiState.map { it.episode }.filterNotNull().first()
            val resolved = chapterResolver.chaptersFor(episode)
            if (episodeIdState.value != episodeId) return@launch
            chaptersState.value = resolved
            chaptersLoadingState.value = false
        }
    }

    /**
     * Plays the episode from a position — what tapping a chapter does.
     *
     * @param positionMs where to start.
     * @param onPlaying invoked once the player has it.
     */
    fun playFrom(positionMs: Long, onPlaying: () -> Unit) {
        val episodeId = episodeIdState.value ?: return
        viewModelScope.launch {
            if (episodePlayer.playFrom(episodeId, positionMs)) onPlaying()
        }
    }

    /** Queues the episode to play after the one playing now. */
    fun playNext() {
        val episodeId = episodeIdState.value ?: return
        viewModelScope.launch { episodePlayer.playNext(episodeId) }
    }

    /** Puts the episode at the end of the queue. */
    fun addToQueue() {
        val episodeId = episodeIdState.value ?: return
        viewModelScope.launch { episodePlayer.addToQueue(episodeId) }
    }

    /**
     * Starts, cancels, retries or deletes the offline copy.
     *
     * The same mapping the show page's toggle uses, so the identical chip on two screens does the
     * identical thing.
     */
    fun toggleDownload() {
        val episode = uiState.value.episode ?: return
        viewModelScope.launch {
            when (episode.downloadState) {
                DownloadState.NOT_DOWNLOADED, DownloadState.FAILED ->
                    downloadRepository.download(episode.id)

                DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.COMPLETED ->
                    downloadRepository.removeDownload(episode.id)
            }
        }
    }

    /**
     * Marks the episode played, or puts it back to unplayed.
     *
     * @param isPlayed what to mark it as.
     */
    fun setPlayed(isPlayed: Boolean) {
        val episodeId = episodeIdState.value ?: return
        viewModelScope.launch { episodePlayer.setPlayed(episodeId, isPlayed) }
    }

    private companion object {
        /** Keeps the sheet's state across a rotation or a fold. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

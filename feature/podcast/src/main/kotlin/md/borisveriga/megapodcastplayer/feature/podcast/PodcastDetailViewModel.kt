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
import kotlinx.coroutines.withTimeoutOrNull
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.data.chapters.ChapterResolver
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.data.export.DownloadExporter
import md.borisveriga.megapodcastplayer.core.data.export.ExportNetwork
import md.borisveriga.megapodcastplayer.core.data.export.ExportProgress
import md.borisveriga.megapodcastplayer.core.data.export.ExportRun
import md.borisveriga.megapodcastplayer.core.data.export.ExportSummary
import md.borisveriga.megapodcastplayer.core.data.export.exportNetworkFor
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadFolderRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.media.NetworkStatus
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.VideoQualitySource
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadFolders
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import md.borisveriga.megapodcastplayer.core.model.EpisodeSort
import md.borisveriga.megapodcastplayer.core.model.OpenPlayerAs
import md.borisveriga.megapodcastplayer.core.model.PlayControl
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.PlayerCommand
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import md.borisveriga.megapodcastplayer.core.model.SwipeDownload
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.filterBy
import md.borisveriga.megapodcastplayer.core.model.playTransition
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoIdOrNull

/**
 * State rendered by the podcast detail screen.
 *
 * @property podcast the show; null while loading or after it has been removed.
 * @property episodes its episodes, newest first.
 * @property isLoading true until the first database emission arrives.
 * @property isRefreshing true while a hand-asked refresh is re-fetching this show's feed; ends in
 *   a snackbar either way. The empty state's button is what asks for one — the pull rebuilds.
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
 * @property exportProgress how far a *Download and export* of this show has got, or null when none
 *   is running. Held so the menu can say so, rather than offering to start a second one.
 * @property unmeteredOnly whether downloads wait for Wi-Fi, which is why a queued video has not
 *   started; the episode sheet says so as the audio's snackbar does.
 * @property videoDownloads every YouTube episode's downloaded video, keyed by episode id; an episode
 *   without one is absent. Read by the sheet's *Download video* button.
 * @property videoQualities the renditions the open episode's video comes in, lowest first, once
 *   asked for with [PodcastDetailViewModel.loadVideoQualities]; null while not yet known.
 * @property videoQualitiesFailed true when that lookup failed, so the dialog can say so rather
 *   than wait forever.
 * @property isOnline whether there is a network to fetch a picture over; see [canPlayVideo].
 * @property swipeDownload what the download swipe fetches, from the app's download settings; see
 *   [swipeDownloadsVideo].
 * @property downloadFolders the user's download folders, for the episode sheet's "Save to" picker
 *   and for naming the folder a download went to.
 * @property youTubeSource where YouTube shows are read from and played by; see [isOfficialYouTube].
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
    val exportProgress: ExportProgress? = null,
    val videoDownloads: Map<String, VideoDownload> = emptyMap(),
    val unmeteredOnly: Boolean = false,
    val videoQualities: List<VideoQuality>? = null,
    val videoQualitiesFailed: Boolean = false,
    val isOnline: Boolean = true,
    val swipeDownload: SwipeDownload = SwipeDownload.DEFAULT,
    val downloadFolders: DownloadFolders = DownloadFolders.NONE,
    val youTubeSource: YouTubeSource = YouTubeSource.DEFAULT,
) {
    /**
     * Whether this is a YouTube show under the official source.
     *
     * The one fact that changes what the page offers: such a show's episodes play in YouTube's own
     * player and nowhere else, so nothing here may hand one to the app's player — not the row's
     * buttons, not the sheet, not *Play next*, not a download in any form. The rows themselves are
     * the repository's business and already come cut and with their downloads hidden.
     */
    val isOfficialYouTube: Boolean
        get() = podcast?.source == PodcastSource.YOUTUBE && youTubeSource == YouTubeSource.OFFICIAL

    /**
     * Whether an episode gets the actions only a picture has: *Play video*, the quality dialog, the
     * video download.
     *
     * Only a YouTube episode has a picture, and under the official source its one play button *is*
     * the picture, so there is no second way to watch it and nothing to download.
     *
     * @param episode the episode asked about.
     */
    fun offersVideoActions(episode: Episode): Boolean =
        !isOfficialYouTube && youTubeVideoIdOrNull(episode.audioUrl) != null

    /**
     * Whether *Download and export* has anything to work on: episodes on screen, of a show whose
     * episodes can be downloaded at all.
     */
    val canExport: Boolean get() = hasExportableEpisodes && !isOfficialYouTube

    /**
     * Whether the download swipe on this episode fetches its picture as well as its sound.
     *
     * Only when the settings ask for video and the episode has one: an RSS episode is downloaded
     * as audio whichever is set.
     *
     * @param episode the episode asked about.
     */
    fun swipeDownloadsVideo(episode: Episode): Boolean =
        swipeDownload == SwipeDownload.AUDIO_AND_VIDEO && youTubeVideoIdOrNull(episode.audioUrl) != null

    /**
     * Whether an episode's picture could be shown right now: there is a network to stream it
     * over, or the video is on the phone.
     *
     * What *Play video* is enabled by. Pressed without either, it opened a video screen that could
     * only say the picture was unavailable; the button now says why before it is pressed.
     *
     * @param episodeId the episode asked about.
     */
    fun canPlayVideo(episodeId: String): Boolean =
        isOnline || videoDownloads[episodeId]?.isComplete == true

    /** The episode the sheet is about, or null when it is closed or the episode has gone. */
    val openEpisode: Episode? get() = episodes.firstOrNull { it.id == openEpisodeId }

    /** The open episode's downloaded video, or null when it has none or the sheet is closed. */
    val openVideoDownload: VideoDownload? get() = openEpisodeId?.let(videoDownloads::get)

    /**
     * Whether the list on screen has anything in it, which is what *Download and export* covers.
     *
     * Not "whether anything is downloaded": downloading what is missing is half of what the entry
     * does, so a show with no downloads at all is exactly the one it is for.
     */
    val hasExportableEpisodes: Boolean get() = episodes.filterBy(settings.episodeFilter).isNotEmpty()
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
 * What surrounds the show's own rows on this page, combined into one for the same reason as
 * [ShowPreferences].
 *
 * @property transient the screen's own short-lived state.
 * @property export a *Download and export* of this show, if one was started.
 * @property videoDownloads every episode's downloaded video, by episode id.
 * @property isOnline whether there is a network to fetch a picture over.
 * @property folders the user's download folders.
 */
private data class Surroundings(
    val transient: PodcastDetailViewModel.TransientState,
    val export: ExportRun?,
    val videoDownloads: Map<String, VideoDownload>,
    val isOnline: Boolean,
    val folders: DownloadFolders,
)

/**
 * The three preference sources the show page reads, combined into one so [combine] stays within
 * its typed arity.
 *
 * @property show this show's own settings.
 * @property playback the app-wide playback settings, read only for the rate.
 * @property downloads the app-wide download settings, read only for the auto-download answer.
 * @property youTubeSource where YouTube shows are read from and played by.
 */
private data class ShowPreferences(
    val show: ShowSettings,
    val playback: PlaybackSettings,
    val downloads: DownloadSettings,
    val youTubeSource: YouTubeSource,
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
     * what it *found*, a rebuild reports how much the feed lists — which is the one figure that
     * says whether the rebuild fixed anything.
     *
     * @property episodeCount episodes the feed yielded.
     * @property keptDownloadCount downloaded episodes the feed no longer lists, which stay in the
     *   show because of their download. Said when it is not zero: otherwise the list is longer
     *   than the count just reported, and holds episodes the user knows they took off the playlist.
     */
    data class Rebuilt(
        val episodeCount: Int,
        val keptDownloadCount: Int = 0,
    ) : PodcastDetailMessage

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
     * *Play video* was pressed and the player did not take the episode in time.
     *
     * Nothing is opened in that case — the video screen would show whatever was loaded before —
     * and a press that visibly does nothing reads as a button that is broken.
     */
    data object VideoNotStarted : PodcastDetailMessage

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
     * An episode was queued for download.
     *
     * Worth confirming because the download itself may not start for a while — "Wi-Fi only" is on
     * by default, so a tap on mobile data appears to do nothing at all.
     *
     * @property title the episode's title.
     * @property waitingForWifi whether the download is waiting for an unmetered network.
     * @property folderName the folder the download is filed under, said so that a swipe — which
     *   has no room to ask — still tells the user where it went; null for *Downloads*.
     */
    data class DownloadQueued(
        val title: String,
        val waitingForWifi: Boolean,
        val folderName: String? = null,
    ) : PodcastDetailMessage

    /**
     * A downloaded episode was removed from the device.
     *
     * @property title the episode's title.
     */
    data class DownloadRemoved(val title: String) : PodcastDetailMessage

    /**
     * A YouTube episode's video was queued for download, with its audio if that was missing.
     *
     * @property title the episode's title.
     * @property quality the rendition asked for.
     * @property waitingForWifi whether it waits for an unmetered network, which the message then
     *   says, as the audio's does: a download that does not start reads otherwise as one that
     *   failed.
     * @property folderName the folder the episode is filed under; null for *Downloads*.
     */
    data class VideoDownloadQueued(
        val title: String,
        val quality: VideoQuality,
        val waitingForWifi: Boolean = false,
        val folderName: String? = null,
    ) : PodcastDetailMessage

    /**
     * An episode's downloaded video was deleted, or its download called off; the audio stays.
     *
     * @property title the episode's title.
     * @property wasComplete true when a finished file was deleted, false when a transfer was
     *   cancelled — the two are worded differently, because a cancelled transfer deleted nothing.
     */
    data class VideoDownloadRemoved(val title: String, val wasComplete: Boolean) :
        PodcastDetailMessage

    /**
     * A *Download and export* of this show was started.
     *
     * Confirmed because the work happens out of sight, in a notification the user may have
     * silenced, and the menu closing is otherwise the only sign the tap did anything.
     *
     * @property waitingForWifi whether the run waits for an unmetered network before it starts:
     *   something is left to download and "Wi-Fi only" is on. That can hold the export back for as
     *   long as the user is away from Wi-Fi, so it is said.
     */
    data class ExportStarted(val waitingForWifi: Boolean) : PodcastDetailMessage

    /**
     * An export this screen watched run has finished.
     *
     * @property summary what became of each episode.
     */
    data class ExportFinished(val summary: ExportSummary) : PodcastDetailMessage

    /** An export could not reach the folder at all, so nothing was copied. */
    data object ExportFailed : PodcastDetailMessage
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
 * @property downloadExporter downloads this show's episodes and copies them to a folder, in the
 *   background.
 * @property videoQualitySource asks the extractor which renditions a YouTube episode comes in, for
 *   the sheet's *Download video* dialog.
 * @property crashReporter where a failed rendition lookup goes; the dialog shows a sentence, the
 *   report keeps the cause.
 * @param savedStateHandle carries the `podcastId` navigation argument.
 */
@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val episodePlayer: EpisodePlayer,
    private val downloadRepository: DownloadRepository,
    private val folderRepository: DownloadFolderRepository,
    private val chapterResolver: ChapterResolver,
    private val showSettings: ShowSettingsRepository,
    private val playbackRepository: PlaybackRepository,
    private val connection: PlaybackConnection,
    private val downloadExporter: DownloadExporter,
    private val videoQualitySource: VideoQualitySource,
    private val crashReporter: CrashReporter,
    private val networkStatus: NetworkStatus,
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
     * True from the moment this screen starts an export until that export has been reported.
     *
     * Lets a run that fails before it was ever seen running still be announced. WorkManager can
     * go straight from enqueued to failed in one emission, and without this the user would be
     * told the export started and then nothing more.
     */
    private var expectingExportOutcome = false

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
        combine(
            transientState,
            downloadExporter.observe(podcastId),
            downloadRepository.observeVideoDownloads(),
            networkStatus.observeOnline(),
            folderRepository.observeFolders(),
            ::Surroundings,
        ),
        nowPlaying,
        combine(
            showSettings.observeSettings(podcastId),
            playbackRepository.observePlaybackSettings(),
            downloadRepository.observeDownloadSettings(),
            repository.observeYouTubeSource(),
            ::ShowPreferences,
        ),
    ) { podcast, episodes, surroundings, playing, preferences ->
        val transient = surroundings.transient
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
            unmeteredOnly = preferences.downloads.unmeteredOnly,
            youTubeSource = preferences.youTubeSource,
            exportProgress = (surroundings.export as? ExportRun.Running)?.progress,
            videoDownloads = surroundings.videoDownloads,
            videoQualities = transient.videoQualities,
            videoQualitiesFailed = transient.videoQualitiesFailed,
            isOnline = surroundings.isOnline,
            swipeDownload = preferences.downloads.swipeDownload,
            downloadFolders = surroundings.folders,
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

        viewModelScope.launch { reportExportOutcomes() }
    }

    /**
     * Downloads the episodes on screen, then copies them into a named folder the user placed.
     *
     * "On screen" is this show's filter as it is now; the run works out the episodes from it when it
     * starts. Runs in the background and outlives this screen; see [DownloadExporter]. Ignored while
     * a run for this show is already going, which the menu also prevents.
     *
     * @param treeUri the location the picker returned.
     * @param folderName the name the user gave the folder.
     */
    fun downloadAndExport(treeUri: String, folderName: String) {
        // Nothing of an official YouTube show can be downloaded, so nothing of it can be exported.
        if (uiState.value.isOfficialYouTube) return
        if (uiState.value.exportProgress != null || folderName.isBlank()) return
        expectingExportOutcome = true
        viewModelScope.launch {
            // Read from storage rather than from [uiState]: the picker can outlive this process, and
            // a view model rebuilt to receive its result still holds the default state — whose
            // filter is "All", which would download the whole show. A second run while one is
            // going is refused by the exporter itself, so the stale guard above is only a shortcut.
            val filter = showSettings.observeSettings(podcastId).first().episodeFilter
            val unmeteredOnly = downloadRepository.observeDownloadSettings().first().unmeteredOnly
            val network = exportNetworkFor(
                episodes = repository.observeEpisodes(podcastId).first(),
                filter = filter,
                unmeteredOnly = unmeteredOnly,
            )
            downloadExporter.start(podcastId, treeUri, folderName.trim(), filter, network)
            val waitingForWifi = network == ExportNetwork.UNMETERED
            transientState.value = transientState.value.copy(
                message = PodcastDetailMessage.ExportStarted(waitingForWifi),
            )
        }
    }

    /**
     * Says how an export ended, when this screen saw it running.
     *
     * Only a run that was seen *running*, or that this screen has just started, is reported.
     * WorkManager replays a finished run to every new observer, and announcing last week's export
     * each time the show is opened would be noise; a run that ended while the user was elsewhere has
     * already said so in its notification.
     *
     * A run started here is trusted only to *fail* unseen. A success always passes through running
     * first, so a success that arrives unseen is the previous run's, replayed.
     */
    private suspend fun reportExportOutcomes() {
        var sawRunning = false
        downloadExporter.observe(podcastId).collect { run ->
            val message = when (run) {
                is ExportRun.Running -> null
                is ExportRun.Finished -> PodcastDetailMessage.ExportFinished(run.summary)
                ExportRun.Failed -> PodcastDetailMessage.ExportFailed
                null -> null
            }
            val failedUnseen = run == ExportRun.Failed && expectingExportOutcome
            if (message != null && (sawRunning || failedUnseen)) {
                transientState.value = transientState.value.copy(message = message)
                expectingExportOutcome = false
            }
            sawRunning = run is ExportRun.Running
        }
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
     * Re-reads this show's feed from scratch and makes the stored list match it.
     *
     * The escape hatch for a list a refresh cannot repair — episodes the publisher withdrew, a feed
     * re-issued under new GUIDs, a playlist whose stored order has drifted — where every further
     * refresh merges into the same wrong list. Episodes the feed still lists keep their progress,
     * played flag and download; a hand-made order is replaced by the feed's.
     *
     * One of those it now repairs in two steps rather than one. A feed re-issued under new GUIDs
     * makes every stored episode one the feed no longer lists, and the downloaded ones are kept
     * beside their re-issued twins until their downloads are deleted. Keeping a download nobody
     * asked to lose is worth a duplicate row that says why it is there.
     *
     * No download is deleted, whatever the feed withdrew. It used to free the downloads of the
     * episodes it dropped, which made taking a video off a playlist the way to lose the copy on
     * the device; the repository now keeps those episodes instead, and the message counts them.
     */
    fun rebuild() {
        if (transientState.value.isBusy) return
        transientState.value = TransientState(isRebuilding = true)

        viewModelScope.launch {
            val result = repository.rebuild(podcastId)
            transientState.value = TransientState(
                isRebuilding = false,
                message = result.fold(
                    onSuccess = { rebuilt ->
                        PodcastDetailMessage.Rebuilt(
                            episodeCount = rebuilt.episodeCount,
                            keptDownloadCount = rebuilt.keptDownloadIds.size,
                        )
                    },
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
        if (opensEmbedded(episodeId)) return
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
     * Plays an episode as sound — what *Play audio* on its sheet does, and the sheet's one play
     * button on an episode with no picture.
     *
     * Not the row button's toggle, which it used to share: on the episode already playing that
     * paused it, and on one playing as video the user had asked for sound and got silence. The
     * episode the player holds is left running and only the player's face changes; see
     * [playTransition].
     *
     * @param episodeId the episode to listen to.
     * @param onPlaying invoked once the player has the episode, so the caller can open the audio
     *   player. Not called when the episode has gone.
     */
    fun listenToEpisode(episodeId: String, onPlaying: () -> Unit) =
        press(PlayControl.PLAY_AUDIO, episodeId, onPlaying)

    /**
     * Plays a YouTube episode and hands over to the video screen once the player holds it.
     *
     * The episode the player already holds is not started again: it carries on from where it is,
     * and the caller switches the player's face in place. See [playTransition].
     *
     * Waits for the player to report the episode as loaded before calling [onWatching]: the video
     * screen leaves at once when the episode loaded has no picture, and until the swap lands that
     * is still whatever was playing before. The wait is bounded, and when it runs out nothing is
     * opened — the episode loaded is then some other one, and a video screen would show its
     * picture instead — and the screen says the video did not start.
     *
     * @param episodeId the episode to watch.
     * @param onWatching invoked once the player holds the episode, so the caller can open the
     *   video screen. Not called when the episode has gone or the player never loaded it.
     */
    fun watchEpisode(episodeId: String, onWatching: () -> Unit) =
        press(PlayControl.PLAY_VIDEO, episodeId, onWatching)

    /**
     * Does what pressing [control] on an episode means: the one path behind the three controls.
     *
     * @param control the control pressed.
     * @param episodeId the episode it belongs to.
     * @param onOpenPlayer invoked when the press opens the player, once the player has the
     *   episode. Which face is the control's own, and the caller's to pass on.
     */
    private fun press(control: PlayControl, episodeId: String, onOpenPlayer: () -> Unit) {
        // The route never gets here for such an episode — it opens YouTube's own player instead,
        // see opensEmbedded — so this is the last line: whatever reaches the app's player with a
        // YouTube episode under the official source is refused below it anyway, with an error.
        if (opensEmbedded(episodeId)) return
        val transition = playTransition(
            control = control,
            isLoaded = uiState.value.nowPlaying.episodeId == episodeId,
        )
        viewModelScope.launch {
            val started = when (transition.command) {
                PlayerCommand.START -> episodePlayer.play(episodeId)

                PlayerCommand.CARRY_ON -> {
                    connection.play()
                    true
                }

                PlayerCommand.TOGGLE -> {
                    connection.togglePlayPause()
                    true
                }
            }
            val message = when {
                !started -> PodcastDetailMessage.EpisodeUnavailable

                transition.openAs == null -> null

                // A picture is only opened on the episode it was asked for.
                transition.openAs == OpenPlayerAs.VIDEO && !awaitLoaded(episodeId) ->
                    PodcastDetailMessage.VideoNotStarted

                else -> {
                    onOpenPlayer()
                    null
                }
            }
            if (message != null) transientState.value = transientState.value.copy(message = message)
        }
    }

    /**
     * Waits, for a bounded while, for the player to say it holds [episodeId].
     *
     * @param episodeId the episode just handed to the player.
     * @return true once the player reports it; false when the wait ran out first.
     */
    private suspend fun awaitLoaded(episodeId: String): Boolean =
        withTimeoutOrNull(WATCH_LOAD_TIMEOUT_MS) {
            connection.playbackState.first { it.episodeId == episodeId }
        } != null

    /**
     * Asks which renditions an episode's video comes in, for the *Download video* dialog.
     *
     * The answer lands in [PodcastDetailUiState.videoQualities], or as
     * [PodcastDetailUiState.videoQualitiesFailed]; a failure is reported, not thrown. Dropped when
     * the sheet has closed or moved to another episode by the time it arrives.
     *
     * @param episodeId the episode whose sheet asked; must be a YouTube episode to have an answer.
     */
    fun loadVideoQualities(episodeId: String) {
        if (opensEmbedded(episodeId)) return
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        val videoId = youTubeVideoIdOrNull(episode.audioUrl) ?: return
        transientState.value = transientState.value.copy(
            videoQualities = null,
            videoQualitiesFailed = false,
        )
        viewModelScope.launch {
            val result = suspendRunCatching { videoQualitySource.qualitiesOf(videoId) }
            result.exceptionOrNull()?.let { crashReporter.recordNonFatal(NON_FATAL_QUALITIES, it) }
            if (transientState.value.openEpisodeId != episodeId) return@launch
            transientState.value = transientState.value.copy(
                videoQualities = result.getOrNull(),
                videoQualitiesFailed = result.isFailure,
            )
        }
    }

    /**
     * Downloads an episode's video at [quality], with its audio if that is not on the device yet.
     *
     * @param episodeId the episode.
     * @param quality the rendition to keep; replaces one kept at another quality.
     * @param destination the folder to file the episode under, from the sheet's picker; by default
     *   the one it is in, else the default folder.
     */
    fun downloadVideo(
        episodeId: String,
        quality: VideoQuality,
        destination: DownloadDestination = DownloadDestination.Unspecified,
    ) {
        if (opensEmbedded(episodeId)) return
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        // Named before the request, from the folders as the screen has them: the request is what
        // files the episode, and the message is about where that put it.
        val folderName = folderNameFor(episodeId, destination)
        viewModelScope.launch {
            transientState.value = transientState.value.copy(
                message = if (downloadRepository.downloadVideo(episodeId, quality, destination)) {
                    PodcastDetailMessage.VideoDownloadQueued(
                        title = episode.title,
                        quality = quality,
                        waitingForWifi = downloadRepository.observeDownloadSettings().first().unmeteredOnly,
                        folderName = folderName,
                    )
                } else {
                    PodcastDetailMessage.EpisodeUnavailable
                },
            )
        }
    }

    /**
     * Deletes an episode's downloaded video, or calls off one still arriving; the audio stays.
     *
     * @param episodeId the episode.
     */
    fun removeVideoDownload(episodeId: String) {
        if (opensEmbedded(episodeId)) return
        val state = uiState.value
        val episode = state.episodes.firstOrNull { it.id == episodeId } ?: return
        val wasComplete = state.videoDownloads[episodeId]?.isComplete == true
        viewModelScope.launch {
            downloadRepository.removeVideoDownload(episodeId)
            transientState.value = transientState.value.copy(
                message = PodcastDetailMessage.VideoDownloadRemoved(episode.title, wasComplete),
            )
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
        if (opensEmbedded(episodeId)) return
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
    fun togglePlay(episodeId: String, onPlaying: () -> Unit) =
        press(PlayControl.ROW_BUTTON, episodeId, onPlaying)

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
            videoQualities = null,
            videoQualitiesFailed = false,
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
            videoQualities = null,
            videoQualitiesFailed = false,
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
        if (opensEmbedded(episodeId)) return
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
        if (opensEmbedded(episodeId)) return
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            when (episode.downloadState) {
                // A failed download is retried rather than cleared: the user tapping the button
                // again plainly means "try that again".
                DownloadState.NOT_DOWNLOADED, DownloadState.FAILED -> fetch(episode, DownloadDestination.Unspecified)

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

    /**
     * Downloads an episode's audio into a folder, from the episode sheet's *Download* with its
     * "Save to" picker.
     *
     * Only ever a download: the sheet's button means "delete" once the episode is on the device,
     * and that path asks first and goes through [toggleDownload].
     *
     * @param episodeId the episode.
     * @param destination the folder chosen in the sheet.
     */
    fun download(episodeId: String, destination: DownloadDestination) {
        if (opensEmbedded(episodeId)) return
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch { fetch(episode, destination) }
    }

    /**
     * Asks for an episode's audio and says what happened, naming the folder it went to.
     *
     * @param episode the episode to download.
     * @param destination the folder to file it under.
     */
    private suspend fun fetch(episode: Episode, destination: DownloadDestination) {
        val folderName = folderNameFor(episode.id, destination)
        transientState.value = transientState.value.copy(
            message = if (downloadRepository.download(episode.id, destination)) {
                PodcastDetailMessage.DownloadQueued(
                    title = episode.title,
                    waitingForWifi = downloadRepository.observeDownloadSettings().first().unmeteredOnly,
                    folderName = folderName,
                )
            } else {
                PodcastDetailMessage.EpisodeUnavailable
            },
        )
    }

    /**
     * The name of the folder a download asked for now would be filed under, or null for
     * *Downloads* — which needs no saying, since it is where every download used to go.
     *
     * @param episodeId the episode being downloaded.
     * @param destination what was asked for.
     */
    private fun folderNameFor(episodeId: String, destination: DownloadDestination): String? {
        val folders = uiState.value.downloadFolders
        val folderId = folders.folderForDownload(episodeId, destination) ?: return null
        return folders.folders.firstOrNull { it.id == folderId }?.name
    }

    /**
     * The download swipe on an episode's row: [toggleDownload], except in what a download fetches.
     *
     * What it fetches follows [PodcastDetailUiState.swipeDownload]. Set to audio and video, a
     * YouTube episode's picture comes with its sound, at the quality last chosen on the video
     * screen; the video download fetches the audio itself, so nothing else is asked for. The
     * episode sheet keeps its two separate buttons and does not come through here: the setting is
     * about the one gesture that has no room to ask.
     *
     * Cancelling and deleting are the toggle's, unchanged.
     *
     * @param episodeId the episode swiped.
     */
    fun swipeDownload(episodeId: String) {
        if (opensEmbedded(episodeId)) return
        val state = uiState.value
        val episode = state.episodes.firstOrNull { it.id == episodeId } ?: return
        val fetches = episode.downloadState == DownloadState.NOT_DOWNLOADED ||
            episode.downloadState == DownloadState.FAILED
        if (!fetches || !state.swipeDownloadsVideo(episode)) {
            toggleDownload(episodeId)
            return
        }
        viewModelScope.launch {
            downloadVideo(episodeId, playbackRepository.observeVideoQuality().first())
        }
    }

    /** Clears the current message once its snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null)
    }

    /**
     * Whether a press on [episodeId] opens YouTube's own player rather than the app's.
     *
     * True for any episode of a YouTube show under the official source — see
     * [PodcastDetailUiState.isOfficialYouTube]. The route asks this before it asks anything else of
     * this view model about the episode, and the methods that would start the app's player or the
     * download stack ask it again and do nothing when it is true. Asked per episode rather than per
     * show only so that the answer stays with the thing it is about.
     *
     * @param episodeId the episode pressed.
     */
    fun opensEmbedded(episodeId: String): Boolean {
        val state = uiState.value
        return state.isOfficialYouTube &&
            state.episodes.any { it.id == episodeId && youTubeVideoIdOrNull(it.audioUrl) != null }
    }

    internal data class TransientState(
        val openEpisodeId: String? = null,
        val chapters: EpisodeChapters = EpisodeChapters(),
        val isChaptersLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val isAutoRefreshing: Boolean = false,
        val isRebuilding: Boolean = false,
        val message: PodcastDetailMessage? = null,
        val videoQualities: List<VideoQuality>? = null,
        val videoQualitiesFailed: Boolean = false,
    ) {
        /**
         * Whether a feed operation of any kind is already running.
         *
         * One guard for all three, so the automatic refresh cannot start on top of a hand-asked
         * one and swallow the answer it was about to give, nor the reverse — and so that neither
         * refresh can land its episodes in a list a rebuild is halfway through replacing.
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

        /** How long *Play video* waits for the player to load the episode before moving on anyway. */
        private const val WATCH_LOAD_TIMEOUT_MS = 3_000L

        /** One message for every failed rendition lookup, so they group into one report. */
        private const val NON_FATAL_QUALITIES = "Episode sheet video qualities lookup failed"

        /**
         * How stale this show's feed must be before opening it re-fetches it.
         *
         * Matches the library's window; the two run against the same feeds and disagreeing about
         * what counts as recent would only produce requests neither of them wanted.
         */
        private val AUTO_REFRESH_STALE_AFTER: Duration = Duration.ofMinutes(15)
    }
}

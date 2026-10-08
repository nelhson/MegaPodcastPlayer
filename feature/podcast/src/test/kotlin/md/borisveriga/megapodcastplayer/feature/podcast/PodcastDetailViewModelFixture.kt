package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.chapters.ChapterResolver
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.data.export.DownloadExporter
import md.borisveriga.megapodcastplayer.core.data.export.ExportRun
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DefaultDownloadFolderRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.NetworkStatus
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.VideoQualitySource
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Before
import org.junit.Rule

/**
 * The mocked world every show page view model test runs in.
 *
 * A base class, as the player's tests use: the fixture is stateful (flows a test pushes values into,
 * and a view model built from them), and JUnit's `@Rule` and `@Before` are inherited, so every
 * subclass gets a fresh one per test. [PodcastDetailViewModelTest] covers the list, its downloads
 * and its refreshes; [PodcastDetailExportTest] covers *Download and export*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class PodcastDetailViewModelFixture {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected val episodes = MutableStateFlow(emptyList<Episode>())
    protected val downloadSettings = MutableStateFlow(DownloadSettings())
    protected val youTubeSource = MutableStateFlow(YouTubeSource.DEFAULT)

    protected lateinit var repository: PodcastRepository
    protected lateinit var episodePlayer: EpisodePlayer
    protected lateinit var downloadRepository: DownloadRepository
    protected lateinit var viewModel: PodcastDetailViewModel

    protected val podcast = Podcast(
        id = "podcast-1",
        itunesId = null,
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        feedUrl = "https://example.com/feed.rss",
        artworkUrl = null,
        description = "",
        addedAt = Instant.EPOCH,
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    protected fun episode(
        id: String,
        downloadState: DownloadState,
        isPlayed: Boolean = false,
    ) = Episode(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = Instant.EPOCH,
        sizeBytes = null,
        isPlayed = isPlayed,
        downloadState = downloadState,
    )

    /** The show as the screen sees it; a test replaces it to make the show a YouTube one. */
    protected val podcastFlow = MutableStateFlow<Podcast?>(null)

    protected lateinit var chapterResolver: ChapterResolver
    protected lateinit var showSettings: ShowSettingsRepository
    protected lateinit var playbackRepository: PlaybackRepository
    protected val storedSettings = MutableStateFlow(ShowSettings.DEFAULT)
    protected lateinit var connection: PlaybackConnection
    protected val playbackState = MutableStateFlow(PlaybackState())
    protected lateinit var downloadExporter: DownloadExporter
    protected val exportRun = MutableStateFlow<ExportRun?>(null)
    protected val videoDownloads = MutableStateFlow(emptyMap<String, VideoDownload>())
    protected lateinit var videoQualitySource: VideoQualitySource
    protected lateinit var networkStatus: NetworkStatus
    protected val online = MutableStateFlow(true)
    protected lateinit var crashReporter: CrashReporter

    /**
     * The real folder repository over an in-memory store, so a test can make a folder and see the
     * show page name it — a mock would only echo what it was told.
     */
    protected lateinit var folderRepository: DefaultDownloadFolderRepository

    @Before
    fun setUp() {
        podcastFlow.value = podcast
        repository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)
        downloadRepository = mockk(relaxed = true)
        chapterResolver = mockk(relaxed = true)
        showSettings = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)
        every { playbackRepository.observePlaybackSettings() } returns flowOf(PlaybackSettings())
        connection = mockk(relaxed = true)

        // Another source `combine` waits on; unstubbed it would hold the whole screen at its
        // initial value, which is the same trap the player's state is stubbed for below.
        every { showSettings.observeSettings(any()) } returns storedSettings
        coEvery { showSettings.update(any(), any()) } answers {
            storedSettings.value = secondArg<(ShowSettings) -> ShowSettings>()(storedSettings.value)
        }

        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters()
        // The screen combines this in, and `combine` emits nothing until every source has emitted
        // once — an unstubbed player would freeze the whole screen at its initial value.
        every { connection.playbackState } returns playbackState

        every { repository.observePodcast(any()) } returns podcastFlow
        every { repository.observeEpisodes(any()) } returns episodes
        // Another source the screen combines in; unstubbed it would freeze the whole state.
        every { repository.observeYouTubeSource() } returns youTubeSource
        every { downloadRepository.observeDownloadSettings() } returns downloadSettings
        coEvery { downloadRepository.download(any()) } returns true
        // Combined into the state like the player's, and frozen just the same if left unstubbed.
        every { downloadRepository.observeVideoDownloads() } returns videoDownloads
        videoQualitySource = mockk()
        networkStatus = mockk()
        every { networkStatus.observeOnline() } returns online
        crashReporter = mockk(relaxed = true)
        folderRepository = DefaultDownloadFolderRepository(UserPreferencesDataSource(InMemoryPreferencesDataStore()))
        downloadExporter = mockk(relaxed = true)
        every { downloadExporter.observe(podcast.id) } returns exportRun

        viewModel = PodcastDetailViewModel(
            repository = repository,
            episodePlayer = episodePlayer,
            downloadRepository = downloadRepository,
            folderRepository = folderRepository,
            chapterResolver = chapterResolver,
            showSettings = showSettings,
            playbackRepository = playbackRepository,
            connection = connection,
            downloadExporter = downloadExporter,
            videoQualitySource = videoQualitySource,
            crashReporter = crashReporter,
            networkStatus = networkStatus,
            savedStateHandle = SavedStateHandle(
                mapOf(PodcastDetailViewModel.PODCAST_ID_ARG to podcast.id),
            ),
        )
    }
}

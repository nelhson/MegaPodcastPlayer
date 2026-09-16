package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import md.borisveriga.megapodcastplayer.core.data.chapters.ChapterResolver
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.data.export.DownloadExporter
import md.borisveriga.megapodcastplayer.core.data.export.ExportRun
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
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

    protected lateinit var chapterResolver: ChapterResolver
    protected lateinit var showSettings: ShowSettingsRepository
    protected lateinit var playbackRepository: PlaybackRepository
    protected val storedSettings = MutableStateFlow(ShowSettings.DEFAULT)
    protected lateinit var connection: PlaybackConnection
    protected val playbackState = MutableStateFlow(PlaybackState())
    protected lateinit var downloadExporter: DownloadExporter
    protected val exportRun = MutableStateFlow<ExportRun?>(null)

    @Before
    fun setUp() {
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

        every { repository.observePodcast(any()) } returns flowOf(podcast)
        every { repository.observeEpisodes(any()) } returns episodes
        every { downloadRepository.observeDownloadSettings() } returns downloadSettings
        coEvery { downloadRepository.download(any()) } returns true
        downloadExporter = mockk(relaxed = true)
        every { downloadExporter.observe(podcast.id) } returns exportRun

        viewModel = PodcastDetailViewModel(
            repository = repository,
            episodePlayer = episodePlayer,
            downloadRepository = downloadRepository,
            chapterResolver = chapterResolver,
            showSettings = showSettings,
            playbackRepository = playbackRepository,
            connection = connection,
            downloadExporter = downloadExporter,
            savedStateHandle = SavedStateHandle(
                mapOf(PodcastDetailViewModel.PODCAST_ID_ARG to podcast.id),
            ),
        )
    }
}

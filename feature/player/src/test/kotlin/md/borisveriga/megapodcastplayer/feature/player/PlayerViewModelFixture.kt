package md.borisveriga.megapodcastplayer.feature.player

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import md.borisveriga.megapodcastplayer.core.data.chapters.ChapterResolver
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.MomentsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.media.EpisodeEndBell
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.SleepTimer
import md.borisveriga.megapodcastplayer.core.media.SleepTimerState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Before
import org.junit.Rule

/**
 * The mocked world every player test runs in.
 *
 * A base class rather than a helper object because the fixture is stateful — five flows the test
 * pushes values into, and a view model built from them — and JUnit's `@Rule` and `@Before` are
 * inherited, so a subclass gets a fresh one per test for free.
 *
 * It exists because there is one view model behind three surfaces — the bar, the sheet and the
 * queue screen — and the tests for it had grown into a single class large enough that finding the
 * one covering a behaviour meant scrolling. The split is by surface, not by convenience:
 * [PlayerViewModelTest] covers what the player does to *playback*, and [PlayerQueueTest] covers
 * what it does to the *queue*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class PlayerViewModelFixture {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected val playbackState = MutableStateFlow(PlaybackState())
    protected val settings = MutableStateFlow(PlaybackSettings())
    protected val queue = MutableStateFlow(emptyList<PlayableEpisode>())
    protected val lastPlayedEpisodeId = MutableStateFlow<String?>(null)
    protected val currentEpisode = MutableStateFlow<Episode?>(null)
    protected val episodeMoments = MutableStateFlow(emptyList<Moment>())

    protected lateinit var connection: PlaybackConnection
    protected lateinit var playbackRepository: PlaybackRepository
    protected lateinit var episodePlayer: EpisodePlayer
    protected lateinit var podcastRepository: PodcastRepository
    protected lateinit var downloadRepository: DownloadRepository
    protected lateinit var momentsRepository: MomentsRepository
    protected lateinit var bell: EpisodeEndBell
    protected lateinit var sleepTimer: SleepTimer
    protected val sleepState = MutableStateFlow(SleepTimerState())
    protected lateinit var chapterResolver: ChapterResolver
    protected lateinit var viewModel: PlayerViewModel

    protected fun playable(id: String, title: String = "Episode $id") = PlayableEpisode(
        episode = episode(id, title),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    protected fun episode(
        id: String,
        title: String = "Episode $id",
        downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
        downloadPercent: Float = 0f,
    ) = Episode(
        id = id,
        podcastId = "podcast-1",
        guid = "guid-$id",
        title = title,
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
        sizeBytes = null,
        downloadState = downloadState,
        downloadPercent = downloadPercent,
    )

    protected fun moment(id: Long, positionMs: Long, note: String? = null) = Moment(
        id = id,
        episodeId = "a",
        positionMs = positionMs,
        note = note,
        createdAtMs = 1_000L,
    )

    /** Puts [id] in the player and makes the repository answer for it. */
    protected fun playing(
        id: String,
        downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
        downloadPercent: Float = 0f,
    ) {
        playbackState.value = PlaybackState(episodeId = id)
        currentEpisode.value = episode(id, downloadState = downloadState, downloadPercent = downloadPercent)
    }

    @Before
    fun setUp() {
        connection = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)
        podcastRepository = mockk(relaxed = true)
        downloadRepository = mockk(relaxed = true)
        momentsRepository = mockk(relaxed = true)
        // The real one: it is a boolean in memory with no collaborators, so a mock would only
        // stand between the view model and the thing under test.
        bell = EpisodeEndBell()
        // Mocked, unlike the bell: it owns a coroutine that counts down in real time, and a test
        // that armed a real one would either wait fifteen minutes or assert nothing.
        sleepTimer = mockk(relaxed = true)
        every { sleepTimer.state } returns sleepState

        chapterResolver = mockk(relaxed = true)
        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters()

        every { connection.playbackState } returns playbackState
        every { playbackRepository.observePlaybackSettings() } returns settings
        every { playbackRepository.observeQueue() } returns queue
        every { playbackRepository.observeLastPlayedEpisodeId() } returns lastPlayedEpisodeId
        every { podcastRepository.observeEpisode(any()) } returns currentEpisode
        every { momentsRepository.observeForEpisode(any()) } returns episodeMoments

        viewModel = PlayerViewModel(
            connection = connection,
            playbackRepository = playbackRepository,
            episodePlayer = episodePlayer,
            podcastRepository = podcastRepository,
            downloadRepository = downloadRepository,
            momentsRepository = momentsRepository,
            sleepTimer = sleepTimer,
            chapterResolver = chapterResolver,
        )
    }
}

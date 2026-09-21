package md.borisveriga.megapodcastplayer.feature.player

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PlayerViewModel].
 *
 * What it does to *playback*: the combining of six sources into one state, and the translation of a
 * button press into a command with the right argument. Both are visible from the outside without a
 * real player, which is why the connection and the repositories are mocked.
 *
 * What it does to the *queue* is [PlayerQueueTest]; the fixture both share is
 * [PlayerViewModelFixture].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest : PlayerViewModelFixture() {

    @Test
    fun `the persisted queue is restored as soon as the player is on screen`() = runTest {
        coVerify { episodePlayer.restoreQueue() }
    }

    @Test
    fun `the ui state combines playback, settings and the queue`() = runTest {
        playbackState.value = PlaybackState(episodeId = "a", title = "Episode a", isPlaying = true)
        settings.value = PlaybackSettings(speed = 1.5f)
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            val state = awaitItem()

            assertEquals("Episode a", state.playback.title)
            assertEquals(1.5f, state.settings.speed, 0.001f)
            assertEquals(listOf("a", "b"), state.queue.map { it.episode.id })
        }
    }

    @Test
    fun `up next excludes the episode that is playing`() = runTest {
        playbackState.value = PlaybackState(episodeId = "b")
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            assertEquals(listOf("c"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `up next hides the loaded episode before the player has said what it is`() = runTest {
        // A cold start, or the service having been killed: binding a controller takes long enough
        // that the queue is on screen first, and until it lands the player reports no episode at
        // all. The durable queue mirrors the player's timeline, so it holds that episode — and
        // without the stored fallback it would be drawn as a queued row in an empty queue.
        playbackState.value = PlaybackState(isConnected = false, episodeId = null)
        lastPlayedEpisodeId.value = "a"
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            assertEquals(listOf("b"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `the loaded episode is the only queue entry, so up next is empty`() = runTest {
        // The shape the bug was reported in: one episode played straight from a show, nothing
        // queued behind it, and a queue screen showing a row the user never added.
        playbackState.value = PlaybackState(isConnected = false, episodeId = null)
        lastPlayedEpisodeId.value = "a"
        queue.value = listOf(playable("a"))

        viewModel.uiState.test {
            assertEquals(emptyList<String>(), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `the player's own episode wins over the stored one`() = runTest {
        // The stored id is only a fallback: it lags a transition by a write, and following it once
        // the player has answered would hide the wrong row.
        playbackState.value = PlaybackState(episodeId = "b")
        lastPlayedEpisodeId.value = "a"
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            assertEquals(listOf("c"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `a queue built without playing anything is entirely up next`() = runTest {
        // Nothing loaded and nothing played recently that is still queued: every row is waiting.
        lastPlayedEpisodeId.value = "played-and-gone"
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            assertEquals(listOf("a", "b"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `up next is the whole queue when nothing in it is playing`() = runTest {
        // The user started an episode straight from a show, so the queue is untouched by it.
        playbackState.value = PlaybackState(episodeId = "elsewhere")
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            assertEquals(listOf("a", "b"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `skipping uses the user's configured intervals, not the defaults`() = runTest {
        settings.value = PlaybackSettings(skipForwardMs = 45_000L, skipBackMs = 15_000L)
        // The view model reads its own state, which only tracks the sources while collected.
        viewModel.uiState.test {
            awaitItem()

            viewModel.skipForward()
            viewModel.skipBack()

            coVerify { connection.skipForward(45_000L) }
            coVerify { connection.skipBack(15_000L) }
        }
    }

    @Test
    fun `setting the speed both applies it and persists it`() = runTest {
        settings.value = PlaybackSettings(speed = 1f)

        viewModel.uiState.test {
            awaitItem()

            viewModel.setSpeed(1.2f)

            // Persisted so the service picks it up again after being killed, and applied so the
            // change is audible now.
            coVerify { playbackRepository.setSpeed(1.2f) }
            coVerify { connection.setSpeed(1.2f) }
        }
    }

    @Test
    fun `a rate the app did not ask for is reported as the show's`() = runTest {
        settings.value = PlaybackSettings(speed = 1f)
        // What `ShowSpeedApplier` does when an episode of a show with its own rate starts: the
        // player runs at 2x while the app-wide preference still says 1x.
        playbackState.value = PlaybackState(episodeId = "a", speed = 2f)

        viewModel.uiState.test {
            assertTrue(awaitItem().hasShowSpeed)
        }
    }

    @Test
    fun `a rate that matches the app's is not badged`() = runTest {
        settings.value = PlaybackSettings(speed = 1.5f)
        playbackState.value = PlaybackState(episodeId = "a", speed = 1.5f)

        viewModel.uiState.test {
            assertFalse(awaitItem().hasShowSpeed)
        }
    }

    @Test
    fun `previewing a speed applies it without writing the preference`() = runTest {
        settings.value = PlaybackSettings(speed = 1f)

        viewModel.uiState.test {
            awaitItem()

            viewModel.previewSpeed(1.35f)

            // The whole point of the split: a drag is heard immediately and costs no disk write,
            // and only the release that ends it is remembered.
            coVerify { connection.setSpeed(1.35f) }
            coVerify(exactly = 0) { playbackRepository.setSpeed(any()) }
        }
    }

    @Test
    fun `marking the current episode played also takes it out of the queue`() = runTest {
        playbackState.value = PlaybackState(episodeId = "a")

        viewModel.uiState.test {
            awaitItem()

            viewModel.markCurrentPlayed()

            // Through the player, which is where marking-and-dequeuing is one behaviour shared
            // with the same action on a list row.
            coVerify { episodePlayer.setPlayed("a", true) }
        }
    }

    @Test
    fun `marking played does nothing when the player is idle`() = runTest {
        viewModel.markCurrentPlayed()

        coVerify(exactly = 0) { playbackRepository.setPlayed(any(), any()) }
    }

    @Test
    fun `play, pause and seek are forwarded to the connection`() = runTest {
        viewModel.togglePlayPause()
        viewModel.seekTo(42_000L)
        viewModel.skipToNext()
        viewModel.skipToPrevious()

        coVerify { connection.togglePlayPause() }
        coVerify { connection.seekTo(42_000L) }
        coVerify { connection.skipToNext() }
        coVerify { connection.skipToPrevious() }
    }

    @Test
    fun `tapping a queued episode plays it`() = runTest {
        viewModel.playQueued("c")

        coVerify { episodePlayer.play("c") }
    }

    @Test
    fun `the outer transport buttons move between chapters when the episode has them`() = runTest {
        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters(
            chapters = listOf(
                Chapter(startMs = 0L, title = "Intro"),
                Chapter(startMs = 120_000L, title = "The interview"),
                Chapter(startMs = 600_000L, title = "Outro"),
            ),
        )

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", positionMs = 200_000L)
            currentEpisode.value = episode("a")
            expectMostRecentItem()

            viewModel.skipToNext()
            viewModel.skipToPrevious()

            // Seeks within the episode, not moves through the queue: an episode with chapters is a
            // list of segments, and "next" means the next segment.
            coVerify { connection.seekTo(600_000L) }
            coVerify { connection.seekTo(120_000L) }
            coVerify(exactly = 0) { connection.skipToNext() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `without chapters the same buttons move between episodes`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a")
            expectMostRecentItem()

            viewModel.skipToNext()
            viewModel.skipToPrevious()

            coVerify { connection.skipToNext() }
            coVerify { connection.skipToPrevious() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the player says which chapter the playhead is in, and where the rest start`() = runTest {
        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters(
            chapters = listOf(
                Chapter(startMs = 0L, title = "Intro"),
                Chapter(startMs = 30_000L, title = "The interview", imageUrl = "https://x/i.png"),
            ),
        )

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(
                episodeId = "a",
                positionMs = 45_000L,
                durationMs = 60_000L,
            )
            currentEpisode.value = episode("a")

            val state = expectMostRecentItem()
            assertEquals("The interview", state.currentChapter?.title)
            assertEquals(listOf(0f, 0.5f), state.chapterMarks)
            // A publisher who attaches chapter artwork means it to be seen.
            assertEquals("https://x/i.png", state.artworkUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the sleep timer is armed with the length that was chosen`() = runTest {
        viewModel.armSleepTimer(30 * 60_000L)

        verify { sleepTimer.armAfter(30 * 60_000L) }
    }

    @Test
    fun `the end-of-episode option is the old bell, unchanged`() = runTest {
        viewModel.armSleepAtEndOfEpisode()

        // One control now, two behaviours behind it. The bell's own rule about which discontinuity
        // counts as an episode ending is untouched — it lives on the player's thread, where it
        // has to.
        verify { sleepTimer.armEndOfEpisode() }
    }

    @Test
    fun `a shake adds a quarter of an hour`() = runTest {
        viewModel.extendSleepTimer()

        verify { sleepTimer.extend(15 * 60_000L) }
    }

    @Test
    fun `the sleep timer offers the chapter playing and the ones after it`() = runTest {
        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters(chapters = THREE_CHAPTERS)

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(
                episodeId = "a",
                positionMs = 700_000L,
                durationMs = 1_800_000L,
            )
            currentEpisode.value = episode("a")

            // Not the intro: "stop at the end of a chapter that has ended" means nothing.
            assertEquals(
                listOf(
                    SleepChapterOption(index = 1, title = "The interview"),
                    SleepChapterOption(index = 2, title = "Listener mail"),
                ),
                expectMostRecentItem().sleepChapterOptions,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a playhead before the first chapter is offered all of them`() = runTest {
        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters(
            chapters = listOf(Chapter(startMs = 30_000L, title = "After the cold open")),
        )

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", positionMs = 5_000L)
            currentEpisode.value = episode("a")

            assertEquals(
                listOf(SleepChapterOption(index = 0, title = "After the cold open")),
                expectMostRecentItem().sleepChapterOptions,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an episode with no chapters offers no end-of-chapter option`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a")
            expectMostRecentItem()

            assertTrue(viewModel.uiState.value.sleepChapterOptions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a chosen chapter ends where the next one starts`() = runTest {
        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters(chapters = THREE_CHAPTERS)

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", positionMs = 120_000L)
            currentEpisode.value = episode("a")
            expectMostRecentItem()

            viewModel.armSleepAtEndOfChapter(1)

            verify {
                sleepTimer.armEndOfChapter(chapterIndex = 1, episodeId = "a", stopAtMs = 1_200_000L)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the last chapter ends with the episode, which is no position at all`() = runTest {
        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters(chapters = THREE_CHAPTERS)

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", positionMs = 120_000L)
            currentEpisode.value = episode("a")
            expectMostRecentItem()

            viewModel.armSleepAtEndOfChapter(2)

            verify { sleepTimer.armEndOfChapter(chapterIndex = 2, episodeId = "a", stopAtMs = null) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a chapter the episode does not have arms nothing`() = runTest {
        coEvery { chapterResolver.chaptersFor(any()) } returns EpisodeChapters(chapters = THREE_CHAPTERS)

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", positionMs = 120_000L)
            currentEpisode.value = episode("a")
            expectMostRecentItem()

            viewModel.armSleepAtEndOfChapter(9)

            verify(exactly = 0) { sleepTimer.armEndOfChapter(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        /** An episode in three parts: ten minutes, ten minutes, and the rest. */
        val THREE_CHAPTERS = listOf(
            Chapter(startMs = 0L, title = "Intro"),
            Chapter(startMs = 600_000L, title = "The interview"),
            Chapter(startMs = 1_200_000L, title = "Listener mail"),
        )
    }
}

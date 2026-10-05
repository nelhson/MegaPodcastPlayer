package md.borisveriga.megapodcastplayer.feature.player

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.OpenPlayerAs
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.PlayerMode
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
    fun `the view model leaves restoring the queue to the service`() = runTest {
        // A restore from here could land after a widget, a headset or the watch had started
        // something, and replace it with a paused queue.
        viewModel.uiState.test { cancelAndIgnoreRemainingEvents() }

        coVerify(exactly = 0) { connection.setQueue(any(), any(), any(), any()) }
        coVerify(exactly = 0) { episodePlayer.resume() }
    }

    @Test
    fun `before the player has been heard from the state is restoring, not emptied`() {
        // The value the first frame is drawn from. Emptied is what shuts the sheet, and a sheet
        // restored open with the activity must still be open when its episode arrives.
        val first = viewModel.uiState.value

        assertTrue(first.isIdle)
        assertFalse(first.isEmptied)
    }

    @Test
    fun `a player that says it holds nothing is emptied`() = runTest {
        playbackState.value = PlaybackState(isConnected = true)

        viewModel.uiState.test {
            assertTrue(awaitItem().isEmptied)
        }
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
        playbackState.value = PlaybackState(episodeId = "b", positionMs = 1_000L)
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
        playbackState.value = PlaybackState(episodeId = "b", positionMs = 1_000L)
        lastPlayedEpisodeId.value = "a"
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            assertEquals(listOf("c"), awaitItem().upNext.map { it.episode.id })
        }
    }

    /**
     * Queueing into an empty player makes that episode the player's current item. Nobody asked to
     * hear it yet, so it is a queued episode like any other — and used to be listed nowhere.
     */
    @Test
    fun `an episode queued into an empty player is a row, not what is playing`() = runTest {
        playbackState.value = PlaybackState(isConnected = true, episodeId = "a")
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(listOf("a", "b"), state.upNext.map { it.episode.id })
            assertEquals(null, state.nowPlaying)
            // The player is showing "a", so only "b" comes after what it shows.
            assertEquals(1, state.followingCount)
        }
    }

    @Test
    fun `starting that episode makes it what is playing, and takes it out of up next`() = runTest {
        playbackState.value = PlaybackState(isConnected = true, episodeId = "a", isPlaying = true)
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("a", state.nowPlaying?.episode?.id)
            assertEquals(listOf("b"), state.upNext.map { it.episode.id })
        }
    }

    /** The stored position sees what the player cannot: an episode paused earlier and reloaded. */
    @Test
    fun `a loaded episode with a stored position was started, whatever the player says`() = runTest {
        playbackState.value = PlaybackState(isConnected = true, episodeId = "a")
        queue.value = listOf(
            playable("a").let { it.copy(episode = it.episode.copy(positionMs = 42_000L)) },
            playable("b"),
        )

        viewModel.uiState.test {
            assertEquals(listOf("b"), awaitItem().upNext.map { it.episode.id })
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
        playbackState.value = PlaybackState(episodeId = "elsewhere", positionMs = 1_000L)
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
        playbackState.value = PlaybackState(episodeId = "a", positionMs = 1_000L)

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
    fun `tapping a queued episode plays it, and says so once the player has it`() = runTest {
        coEvery { episodePlayer.play("c") } returns true
        var opened = false

        viewModel.playQueued("c") { opened = true }

        coVerify { episodePlayer.play("c") }
        assertTrue(opened)
    }

    @Test
    fun `a queued episode that has gone opens nothing`() = runTest {
        coEvery { episodePlayer.play("c") } returns false
        var opened = false

        viewModel.playQueued("c") { opened = true }

        assertFalse(opened)
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

    @Test
    fun `the player opens as video only in video mode on an episode with a picture`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            playbackState.value = PlaybackState(episodeId = "a", youTubeVideoId = VIDEO_ID)
            assertFalse(awaitItem().opensAsVideo)

            playerMode.value = PlayerMode.VIDEO
            assertTrue(awaitItem().opensAsVideo)

            // The mode is kept when a feed episode loads, and simply does not apply to it.
            playbackState.value = PlaybackState(episodeId = "b")
            val feed = awaitItem()
            assertEquals(PlayerMode.VIDEO, feed.mode)
            assertFalse(feed.opensAsVideo)
        }
    }

    @Test
    fun `choosing a face for the player remembers it`() = runTest {
        viewModel.setPlayerMode(PlayerMode.VIDEO)

        coVerify { playbackRepository.setPlayerMode(PlayerMode.VIDEO) }
    }

    // --- the one door into the player: which face an ask opens ---------------

    @Test
    fun `asked for sound, the player opens as the sheet without waiting on the service`() = runTest {
        playerMode.value = PlayerMode.VIDEO
        playbackState.value = PlaybackState(isConnected = false)

        assertEquals(PlayerMode.AUDIO, viewModel.faceFor("a", OpenPlayerAs.AUDIO))
        // Not a moment of the timeout spent: the ask alone answered.
        assertEquals(0L, currentTime)
    }

    @Test
    fun `asked for the picture of the episode loaded, the player opens as video`() = runTest {
        // Whatever the mode was: the ask is what sets it.
        playerMode.value = PlayerMode.AUDIO
        playbackState.value = PlaybackState(isConnected = true, episodeId = "a", youTubeVideoId = VIDEO_ID)

        assertEquals(PlayerMode.VIDEO, viewModel.faceFor("a", OpenPlayerAs.VIDEO))
        assertEquals(PlayerMode.VIDEO, viewModel.faceFor(null, OpenPlayerAs.VIDEO))
    }

    @Test
    fun `asked for a picture, the player waits for the episode it was asked for`() = runTest {
        // Another episode with a picture is still loaded; opening on that would show its picture.
        playbackState.value = PlaybackState(isConnected = true, episodeId = "old", youTubeVideoId = VIDEO_ID)
        var face: PlayerMode? = null
        val asking = launch { face = viewModel.faceFor("a", OpenPlayerAs.VIDEO) }
        runCurrent()
        assertTrue(asking.isActive)

        playbackState.value = PlaybackState(isConnected = true, episodeId = "a", youTubeVideoId = VIDEO_ID)
        asking.join()

        assertEquals(PlayerMode.VIDEO, face)
    }

    @Test
    fun `a picture asked for an episode the player never loads opens nothing`() = runTest {
        playbackState.value = PlaybackState(isConnected = true, episodeId = "old", youTubeVideoId = VIDEO_ID)

        assertEquals(null, viewModel.faceFor("a", OpenPlayerAs.VIDEO))
        // It did wait, and gave up.
        assertTrue(currentTime > 0L)
    }

    @Test
    fun `a picture asked for an episode that has none opens the sheet`() = runTest {
        playbackState.value = PlaybackState(isConnected = true, episodeId = "a")

        assertEquals(PlayerMode.AUDIO, viewModel.faceFor("a", OpenPlayerAs.VIDEO))
    }

    @Test
    fun `asked before the state has filled in, the player still knows it was left as video`() = runTest {
        // Nobody is collecting `uiState` here, which is the cold start the question exists for.
        playerMode.value = PlayerMode.VIDEO
        playbackState.value = PlaybackState(isConnected = true, episodeId = "a", youTubeVideoId = VIDEO_ID)

        assertEquals(PlayerMode.VIDEO, viewModel.faceFor(null, OpenPlayerAs.REMEMBERED))
    }

    @Test
    fun `left in audio, the player opens as the sheet without waiting on the service`() = runTest {
        playbackState.value = PlaybackState(isConnected = false)

        assertEquals(PlayerMode.AUDIO, viewModel.faceFor(null, OpenPlayerAs.REMEMBERED))
        // Not a moment of the timeout spent: the mode alone answered.
        assertEquals(0L, currentTime)
    }

    @Test
    fun `left in video, a service that never says what is loaded opens the sheet`() = runTest {
        playerMode.value = PlayerMode.VIDEO
        playbackState.value = PlaybackState(isConnected = false)

        assertEquals(PlayerMode.AUDIO, viewModel.faceFor(null, OpenPlayerAs.REMEMBERED))
        // It did wait, and gave up: the answer came from the timeout, not from the mode.
        assertTrue(currentTime > 0L)
    }

    @Test
    fun `left in video, a feed episode opens the sheet`() = runTest {
        playerMode.value = PlayerMode.VIDEO
        playbackState.value = PlaybackState(isConnected = true, episodeId = "a")

        assertEquals(PlayerMode.AUDIO, viewModel.faceFor("a", OpenPlayerAs.REMEMBERED))
    }

    @Test
    fun `a remembered face waits for the episode just started, not the one before it`() = runTest {
        // A queue row tapped while a feed episode plays, with the player left in video.
        playerMode.value = PlayerMode.VIDEO
        playbackState.value = PlaybackState(isConnected = true, episodeId = "old")
        var face: PlayerMode? = null
        val asking = launch { face = viewModel.faceFor("a", OpenPlayerAs.REMEMBERED) }
        runCurrent()

        playbackState.value = PlaybackState(isConnected = true, episodeId = "a", youTubeVideoId = VIDEO_ID)
        asking.join()

        assertEquals(PlayerMode.VIDEO, face)
    }

    private companion object {
        /** Any YouTube video id; only its presence matters. */
        const val VIDEO_ID = "niTJ2221aS8"

        /** An episode in three parts: ten minutes, ten minutes, and the rest. */
        val THREE_CHAPTERS = listOf(
            Chapter(startMs = 0L, title = "Intro"),
            Chapter(startMs = 600_000L, title = "The interview"),
            Chapter(startMs = 1_200_000L, title = "Listener mail"),
        )
    }
}

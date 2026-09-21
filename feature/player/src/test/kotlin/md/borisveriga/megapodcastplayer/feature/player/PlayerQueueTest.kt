package md.borisveriga.megapodcastplayer.feature.player

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for everything [PlayerViewModel] does to the queue.
 *
 * Split from [PlayerViewModelTest], which covers what it does to playback. The queue is the one
 * part of the player that the user *edits* — reorders, prunes, empties — and every one of those
 * edits is reversible, so most of what is asserted here is that the undo puts back exactly what
 * was taken away and does it exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerQueueTest : PlayerViewModelFixture() {

    @Test
    fun `a reorder is translated from list positions to player indices`() = runTest {
        // The screen lists what comes after "b", so its index 0 is "c" and its index 1 is "d" —
        // while the player's queue still holds "a" and "b" in front of them. Passing the list
        // indices straight through would move "a" onto "b" and leave the queue in an order the
        // user never asked for.
        playbackState.value = PlaybackState(
            episodeId = "b",
            positionMs = STARTED_MS,
            queueEpisodeIds = listOf("a", "b", "c", "d"),
            queueIndex = 1,
        )
        queue.value = listOf(playable("a"), playable("b"), playable("c"), playable("d"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.moveInUpNext(fromIndex = 1, toIndex = 0)

            coVerify { episodePlayer.moveInQueue(3, 2, listOf("a", "b", "d", "c")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reorder is refused when the player queue disagrees with the stored one`() = runTest {
        // The player has moved on and no longer holds "d"; reordering by position would move
        // whatever now sits at that index. There is no safe interpretation, so nothing happens.
        playbackState.value = PlaybackState(
            episodeId = "b",
            positionMs = STARTED_MS,
            queueEpisodeIds = listOf("a", "b", "c"),
            queueIndex = 1,
        )
        queue.value = listOf(playable("a"), playable("b"), playable("c"), playable("d"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.moveInUpNext(fromIndex = 1, toIndex = 0)

            coVerify(exactly = 0) { episodePlayer.moveInQueue(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reorder off the end of the list does nothing`() = runTest {
        playbackState.value = PlaybackState(
            episodeId = "b",
            positionMs = STARTED_MS,
            queueEpisodeIds = listOf("a", "b", "c"),
            queueIndex = 1,
        )
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.moveInUpNext(fromIndex = 0, toIndex = 5)

            coVerify(exactly = 0) { episodePlayer.moveInQueue(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dropping an episode back where it started does nothing`() = runTest {
        playbackState.value = PlaybackState(
            episodeId = "a",
            positionMs = STARTED_MS,
            queueEpisodeIds = listOf("a", "b", "c"),
            queueIndex = 0,
        )
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.moveInUpNext(fromIndex = 1, toIndex = 1)

            coVerify(exactly = 0) { episodePlayer.moveInQueue(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a removal is offered back, and the undo puts the episode where it was`() = runTest {
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.removeFromQueue("b")

            coVerify { episodePlayer.removeFromQueue("b") }
            assertEquals(QueueMessage.Removed("Episode b"), expectMostRecentItem().message)

            // The queue as it stood *before* the removal, which is the only description of where
            // "b" belongs that survives it. Restoring it by appending would put it after "c".
            viewModel.undoQueueChange()
            coVerify { episodePlayer.restoreAllToQueue(listOf("b"), listOf("a", "b", "c")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the undo is spent once, and does not survive its snackbar`() = runTest {
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.removeFromQueue("b")
            viewModel.undoQueueChange()
            // A second tap on a snackbar that has already been acted on.
            viewModel.undoQueueChange()

            coVerify(exactly = 1) { episodePlayer.restoreAllToQueue(any(), any()) }

            viewModel.removeFromQueue("a")
            // The snackbar timed out rather than being tapped. An undo still armed here would fire
            // against whichever message came next.
            viewModel.onQueueMessageShown()
            viewModel.undoQueueChange()

            coVerify(exactly = 1) { episodePlayer.restoreAllToQueue(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing an episode the queue does not hold reports nothing`() = runTest {
        queue.value = listOf(playable("a"))

        viewModel.uiState.test {
            awaitItem()

            // The gesture raced the player finishing the episode.
            viewModel.removeFromQueue("gone")

            coVerify(exactly = 0) { episodePlayer.removeFromQueue(any()) }
            // Nothing changed, so nothing is emitted and there is no snackbar to dismiss.
            expectNoEvents()
            assertEquals(null, viewModel.uiState.value.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the current episode's download state reaches the ui`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            playing("a", downloadState = DownloadState.DOWNLOADING, downloadPercent = 42f)

            // The most recent, not the next: the episode arriving and its download state arriving
            // are two source changes, and only where they land matters.
            val state = expectMostRecentItem()
            assertEquals(DownloadState.DOWNLOADING, state.download?.state)
            assertEquals(42f, state.download?.percent)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no episode means no download control at all`() = runTest {
        // Null rather than NOT_DOWNLOADED: a download button for an episode that does not exist
        // would offer to fetch nothing.
        assertEquals(null, viewModel.uiState.value.download)
    }

    @Test
    fun `an episode the library has forgotten leaves the download control off`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            playbackState.value = PlaybackState(episodeId = "a", positionMs = STARTED_MS)
            currentEpisode.value = null

            assertEquals(null, expectMostRecentItem().download)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the download state falls back to the last played episode`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            // The controller has not bound yet, so the player names no episode.
            lastPlayedEpisodeId.value = "a"
            currentEpisode.value = episode("a", downloadState = DownloadState.COMPLETED)

            assertEquals(DownloadState.COMPLETED, expectMostRecentItem().download?.state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `progress that rounds to the same percentage does not rebuild the ui state`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a", downloadState = DownloadState.DOWNLOADING, downloadPercent = 42f)
            expectMostRecentItem()

            // A transfer writes to the database several times a second; an identical reading must
            // not recompose the whole sheet.
            currentEpisode.value =
                episode("a", downloadState = DownloadState.DOWNLOADING, downloadPercent = 42f)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an undownloaded episode starts downloading`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a", downloadState = DownloadState.NOT_DOWNLOADED)
            expectMostRecentItem()

            viewModel.toggleCurrentDownload()

            coVerify(exactly = 1) { downloadRepository.download("a") }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed episode is retried rather than removed`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a", downloadState = DownloadState.FAILED)
            expectMostRecentItem()

            viewModel.toggleCurrentDownload()

            coVerify(exactly = 1) { downloadRepository.download("a") }
            coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a transfer in flight is cancelled`() = runTest {
        for (state in listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)) {
            playing("a", downloadState = state)

            viewModel.uiState.test {
                awaitItem()
                viewModel.toggleCurrentDownload()
                cancelAndIgnoreRemainingEvents()
            }
        }

        coVerify(exactly = 2) { downloadRepository.removeDownload("a") }
        coVerify(exactly = 0) { downloadRepository.download(any()) }
    }

    @Test
    fun `a completed episode is deleted`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a", downloadState = DownloadState.COMPLETED)
            expectMostRecentItem()

            viewModel.toggleCurrentDownload()

            coVerify(exactly = 1) { downloadRepository.removeDownload("a") }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling the download with nothing loaded does nothing`() = runTest {
        viewModel.toggleCurrentDownload()

        coVerify(exactly = 0) { downloadRepository.download(any()) }
        coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
    }

    @Test
    fun `marking uses the player's position rather than the one last drawn`() = runTest {
        coEvery { momentsRepository.mark("a", 743_000L) } returns moment(id = 7L, positionMs = 743_000L)

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", positionMs = 743_000L)
            expectMostRecentItem()

            viewModel.markMoment()

            coVerify(exactly = 1) { momentsRepository.mark("a", 743_000L) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a saved moment is announced with the position that was actually kept`() = runTest {
        // A second press, folded into a mark five seconds earlier.
        coEvery { momentsRepository.mark("a", 748_000L) } returns moment(id = 7L, positionMs = 743_000L)

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", positionMs = 748_000L)
            expectMostRecentItem()

            viewModel.markMoment()

            assertEquals(SavedMoment(id = 7L, positionMs = 743_000L), expectMostRecentItem().momentSaved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a mark that could not be saved announces nothing`() = runTest {
        coEvery { momentsRepository.mark(any(), any(), any()) } returns null

        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", positionMs = 743_000L)
            expectMostRecentItem()

            viewModel.markMoment()

            // Read off the state rather than awaited: nothing changed, so there is no new item.
            assertEquals(null, viewModel.uiState.value.momentSaved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pressing with nothing loaded does not reach the repository`() = runTest {
        playbackState.value = PlaybackState(episodeId = null)

        viewModel.markMoment()

        coVerify(exactly = 0) { momentsRepository.mark(any(), any(), any()) }
    }

    @Test
    fun `the moment count follows the episode playing`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a")
            episodeMoments.value = listOf(
                moment(id = 1L, positionMs = 60_000L),
                moment(id = 2L, positionMs = 120_000L),
                moment(id = 3L, positionMs = 180_000L),
            )

            // The count the mark button draws is the size of the list the count now opens onto:
            // one query, one answer.
            assertEquals(3, expectMostRecentItem().momentCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissing stops the player and announces itself`() = runTest {
        playing("a")
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            awaitItem()
            viewModel.dismiss()

            assertTrue(expectMostRecentItem().dismissed)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { episodePlayer.dismiss() }
    }

    @Test
    fun `dismissing with nothing to dismiss does nothing`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = null)
            queue.value = emptyList()

            viewModel.dismiss()

            coVerify(exactly = 0) { episodePlayer.dismiss() }
            // Read off the state rather than awaited: nothing changed, so there is no new item.
            assertFalse(viewModel.uiState.value.dismissed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `undoing a dismissal puts the whole queue back where it was`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "b", positionMs = 90_000L)
            currentEpisode.value = episode("b")
            queue.value = listOf(playable("a"), playable("b"), playable("c"))
            expectMostRecentItem()

            viewModel.dismiss()
            viewModel.undoDismiss()

            // The arrangement as it stood, the episode that was loaded, and the second it stopped
            // at — an undo that restarted the queue from the top would lose an hour of listening.
            coVerify {
                episodePlayer.restoreDismissed(
                    orderedIds = listOf("a", "b", "c"),
                    startEpisodeId = "b",
                    positionMs = 90_000L,
                )
            }
            assertFalse(expectMostRecentItem().dismissed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the dismissal undo is spent once, and does not survive its snackbar`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a")
            queue.value = listOf(playable("a"))
            expectMostRecentItem()

            viewModel.dismiss()
            viewModel.undoDismiss()
            // A second tap on a snackbar that has already been acted on.
            viewModel.undoDismiss()

            coVerify(exactly = 1) { episodePlayer.restoreDismissed(any(), any(), any()) }

            viewModel.dismiss()
            // The snackbar timed out rather than being tapped.
            viewModel.onDismissMessageShown()
            viewModel.undoDismiss()

            // Still one: an undo left armed past the message that offered it would restore a queue
            // the user has since replaced.
            coVerify(exactly = 1) { episodePlayer.restoreDismissed(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearing the queue empties what is next and leaves what is playing`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a")
            queue.value = listOf(playable("a"), playable("b"), playable("c"))
            expectMostRecentItem()

            viewModel.clearQueue()

            // "b" and "c" only: this is Clear queue, not Stop, and the two are separate decisions.
            coVerify { episodePlayer.clearFromQueue(listOf("b", "c")) }
            assertEquals(QueueMessage.Cleared(2), expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearing an already-empty queue does nothing`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a")
            queue.value = listOf(playable("a"))
            expectMostRecentItem()

            viewModel.clearQueue()

            coVerify(exactly = 0) { episodePlayer.clearFromQueue(any()) }
            assertEquals(null, viewModel.uiState.value.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a cleared queue is offered back whole, in the order it was in`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("a")
            queue.value = listOf(playable("a"), playable("b"), playable("c"))
            expectMostRecentItem()

            viewModel.clearQueue()
            viewModel.undoQueueChange()

            // The whole arrangement, including the episode playing: it is what the removed entries
            // are inserted back into, and their positions are relative to it.
            coVerify {
                episodePlayer.restoreAllToQueue(listOf("b", "c"), listOf("a", "b", "c"))
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the queue names what is playing separately from what is next`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playing("b")
            queue.value = listOf(playable("a"), playable("b"), playable("c"))

            val state = expectMostRecentItem()
            assertEquals("b", state.nowPlaying?.episode?.id)
            assertEquals(listOf("c"), state.upNext.map { it.episode.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}

/** A position that says "this episode has been started": the queue lists an unstarted one as a row. */
private const val STARTED_MS = 1_000L

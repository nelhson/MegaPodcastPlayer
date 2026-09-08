package md.borisveriga.megapodcastplayer.feature.moments

import android.net.Uri
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.backup.BackupFileStore
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.MomentsRepository
import md.borisveriga.megapodcastplayer.core.model.MOMENT_PRE_ROLL_MS
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [MomentsViewModel].
 *
 * The repository is mocked: what this class contributes is the pre-roll on playback, the undo that
 * has to carry a whole deleted row, and the refusal to write an empty document — none of which
 * needs a database to be visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MomentsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val moments = MutableStateFlow(emptyList<MomentWithEpisode>())

    private val repository = mockk<MomentsRepository>(relaxed = true)
    private val episodePlayer = mockk<EpisodePlayer>(relaxed = true)
    private val fileStore = mockk<BackupFileStore>(relaxed = true)
    private val uri = mockk<Uri>(relaxed = true)

    private lateinit var viewModel: MomentsViewModel

    private fun entry(id: Long, positionMs: Long = 743_000L, note: String? = null) =
        MomentWithEpisode(
            moment = Moment(
                id = id,
                episodeId = "episode-$id",
                positionMs = positionMs,
                note = note,
                createdAtMs = 1_000L,
            ),
            episodeTitle = "Episode $id",
            showTitle = "Podlodka Podcast",
            showArtworkUrl = null,
            feedUrl = "https://feeds.simplecast.com/podlodka",
            audioUrl = "https://cdn.example.com/$id.mp3",
        )

    @Before
    fun setUp() {
        every { repository.observeMoments() } returns moments
        viewModel = MomentsViewModel(
            momentsRepository = repository,
            episodePlayer = episodePlayer,
            fileStore = fileStore,
            clock = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun `the list starts as loading rather than as empty`() = runTest {
        assertEquals(true, viewModel.uiState.value.isLoading)
        assertEquals(false, viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `an empty database is empty once it has answered`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            assertEquals(true, viewModel.uiState.value.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playing a moment starts a few seconds before the mark`() = runTest {
        viewModel.play(entry(id = 1L, positionMs = 743_000L))

        coVerify(exactly = 1) {
            episodePlayer.playFrom("episode-1", 743_000L - MOMENT_PRE_ROLL_MS)
        }
    }

    @Test
    fun `a moment marked in the first seconds resumes from the start rather than before it`() =
        runTest {
            viewModel.play(entry(id = 1L, positionMs = 3_000L))

            coVerify(exactly = 1) { episodePlayer.playFrom("episode-1", 0L) }
        }

    @Test
    fun `deleting offers the moment back`() = runTest {
        val deleted = entry(id = 1L, note = "the good bit")

        viewModel.uiState.test {
            awaitItem()
            viewModel.delete(deleted)

            assertEquals(MomentsMessage.Deleted(deleted), expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { repository.delete(1L) }
    }

    @Test
    fun `undo re-marks the same spot, note and all`() = runTest {
        val deleted = entry(id = 1L, positionMs = 743_000L, note = "the good bit")

        viewModel.uiState.test {
            awaitItem()
            viewModel.delete(deleted)
            expectMostRecentItem()

            viewModel.undoDelete()

            assertNull(viewModel.uiState.value.message)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { repository.mark("episode-1", 743_000L, "the good bit") }
    }

    @Test
    fun `undo with nothing deleted does nothing`() = runTest {
        viewModel.undoDelete()

        coVerify(exactly = 0) { repository.mark(any(), any(), any()) }
    }

    @Test
    fun `the note editor reads back the row as it currently stands`() = runTest {
        moments.value = listOf(entry(id = 1L, note = "first"))

        viewModel.uiState.test {
            awaitItem()
            viewModel.edit(entry(id = 1L, note = "stale copy"))
            expectMostRecentItem()

            // Edited elsewhere — the player's own note dialog — while this screen held a copy.
            moments.value = listOf(entry(id = 1L, note = "written elsewhere"))

            assertEquals("written elsewhere", expectMostRecentItem().editing?.moment?.note)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving a note closes the editor and writes once`() = runTest {
        moments.value = listOf(entry(id = 1L))

        viewModel.uiState.test {
            awaitItem()
            viewModel.edit(entry(id = 1L))
            expectMostRecentItem()

            viewModel.saveNote("the good bit")

            assertNull(expectMostRecentItem().editing)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { repository.setNote(1L, "the good bit") }
    }

    @Test
    fun `the suggested file name carries the day it was written`() {
        assertEquals("megapodcastplayer-moments-2026-09-07.md", viewModel.suggestedFileName())
    }

    @Test
    fun `an export with nothing in it writes no file`() = runTest {
        coEvery { repository.exportMarkdown() } returns ""

        viewModel.uiState.test {
            awaitItem()
            viewModel.exportTo(uri)

            assertEquals(MomentsMessage.NothingToExport, expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { fileStore.write(any(), any()) }
    }

    @Test
    fun `a written export says so`() = runTest {
        coEvery { repository.exportMarkdown() } returns "# MegaPodcastPlayer moments"
        coEvery { fileStore.write(uri, any()) } returns Result.success(Unit)

        viewModel.uiState.test {
            awaitItem()
            viewModel.exportTo(uri)

            assertEquals(MomentsMessage.Exported, expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a document that could not be written says that instead`() = runTest {
        coEvery { repository.exportMarkdown() } returns "# MegaPodcastPlayer moments"
        coEvery { fileStore.write(uri, any()) } returns Result.failure(RuntimeException("gone"))

        viewModel.uiState.test {
            awaitItem()
            viewModel.exportTo(uri)

            assertEquals(MomentsMessage.ExportFailed, expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

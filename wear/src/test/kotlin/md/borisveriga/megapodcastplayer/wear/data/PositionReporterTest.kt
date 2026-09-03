package md.borisveriga.megapodcastplayer.wear.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the debt the watch owes the phone after playing something itself.
 *
 * The failure worth guarding is silent: a report that did not get through, marked as though it had.
 * The phone would then keep an episode waiting where it stood before the run, and nothing would ever
 * correct it.
 */
class PositionReporterTest {

    private val client = mockk<PhonePlayerClient>(relaxed = true)
    private val store = mockk<WatchEpisodeStore>(relaxed = true)

    private val played = StoredEpisode(
        id = "ep-1",
        title = "The one about batteries",
        positionMs = 900_000L,
        positionReported = false,
    )

    @Test
    fun `a delivered position stops being owed`() = runTest {
        val reporter = reporterHolding(played)
        coEvery { client.send(any()) } returns true

        assertTrue(reporter.report("ep-1"))

        coVerify(exactly = 1) {
            client.send(
                WearCommand.ReportPosition(
                    episodeId = "ep-1",
                    positionMs = 900_000L,
                    isPlayed = false,
                ),
            )
        }
        coVerify(exactly = 1) { store.markPositionReported("ep-1") }
    }

    /** The whole point of the flag: an undelivered report has to stay pending. */
    @Test
    fun `a report the phone did not take is still owed`() = runTest {
        val reporter = reporterHolding(played)
        coEvery { client.send(any()) } returns false

        assertFalse(reporter.report("ep-1"))

        coVerify(exactly = 0) { store.markPositionReported(any()) }
    }

    @Test
    fun `a finished episode is reported as finished`() = runTest {
        val reporter = reporterHolding(played.copy(isPlayed = true, positionMs = 3_599_000L))
        coEvery { client.send(any()) } returns true

        reporter.report("ep-1")

        coVerify(exactly = 1) {
            client.send(
                WearCommand.ReportPosition(
                    episodeId = "ep-1",
                    positionMs = 3_599_000L,
                    isPlayed = true,
                ),
            )
        }
    }

    @Test
    fun `flushing sends only what the phone has not been told`() = runTest {
        val reporter = reporterHolding(
            played,
            played.copy(id = "ep-2", positionReported = true),
            played.copy(id = "ep-3", positionReported = false),
        )
        coEvery { client.send(any()) } returns true

        assertEquals(2, reporter.flush())

        coVerify(exactly = 1) { store.markPositionReported("ep-1") }
        coVerify(exactly = 1) { store.markPositionReported("ep-3") }
        coVerify(exactly = 0) { store.markPositionReported("ep-2") }
    }

    @Test
    fun `an episode the watch no longer holds is not reported`() = runTest {
        val reporter = reporterHolding()

        assertFalse(reporter.report("ep-1"))

        coVerify(exactly = 0) { client.send(any()) }
    }

    /** A reporter over a store holding exactly these episodes. */
    private fun reporterHolding(vararg episodes: StoredEpisode): PositionReporter {
        every { store.episodes } returns MutableStateFlow(episodes.toList())
        return PositionReporter(store, client)
    }
}

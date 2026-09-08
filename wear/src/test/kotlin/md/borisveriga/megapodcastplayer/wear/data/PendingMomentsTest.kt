package md.borisveriga.megapodcastplayer.wear.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * Tests the queue that keeps a mark made out of Bluetooth range.
 *
 * This is the class that decides whether "I marked that on my run" is true afterwards. A position
 * played on the watch can be reconstructed from the watch's own index; a moment cannot be
 * reconstructed from anything, so a dropped message is the whole failure.
 *
 * Robolectric only for the application context's files directory; nothing here touches the Data
 * Layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PendingMomentsTest {

    private val client = mockk<PhonePlayerClient>(relaxed = true)

    @Before
    fun clearTheQueue() {
        RuntimeEnvironment.getApplication().filesDir.resolve("pending-moments.json").delete()
    }

    private fun pendingMoments() = PendingMoments(
        context = RuntimeEnvironment.getApplication(),
        client = client,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `a mark with the phone in range goes straight across and is not kept`() = runTest {
        coEvery { client.send(any()) } returns true
        val moments = pendingMoments()

        assertTrue(moments.mark("ep-1", 743_000L))

        coVerify(exactly = 1) {
            client.send(WearCommand.MarkMoment(episodeId = "ep-1", positionMs = 743_000L))
        }
        // Nothing left over: a flush right afterwards has nothing to send.
        assertEquals(0, moments.flush())
    }

    @Test
    fun `a mark the phone did not take is kept and sent when it comes back`() = runTest {
        coEvery { client.send(any()) } returns false
        val moments = pendingMoments()

        assertFalse(moments.mark("ep-1", 743_000L))

        coEvery { client.send(any()) } returns true
        assertEquals(1, moments.flush())
        coVerify(exactly = 2) {
            client.send(WearCommand.MarkMoment(episodeId = "ep-1", positionMs = 743_000L))
        }
    }

    @Test
    fun `the queue survives the app being restarted`() = runTest {
        coEvery { client.send(any()) } returns false
        pendingMoments().mark("ep-1", 743_000L)

        coEvery { client.send(any()) } returns true
        // A second instance reads what the first one wrote, which is what a cold start does.
        assertEquals(1, pendingMoments().flush())
    }

    @Test
    fun `two marks at the same spot are kept once`() = runTest {
        coEvery { client.send(any()) } returns false
        val moments = pendingMoments()

        moments.mark("ep-1", 743_000L)
        moments.mark("ep-1", 743_000L)

        coEvery { client.send(any()) } returns true
        assertEquals(1, moments.flush())
    }

    @Test
    fun `a phone that goes away mid-flush keeps what it did not take`() = runTest {
        coEvery { client.send(any()) } returns false
        val moments = pendingMoments()
        moments.mark("ep-1", 10_000L)
        moments.mark("ep-1", 20_000L)

        var sent = 0
        coEvery { client.send(any()) } answers { sent++ == 0 }
        assertEquals(1, moments.flush())

        coEvery { client.send(any()) } returns true
        assertEquals(1, moments.flush())
    }

    @Test
    fun `an empty queue asks the phone nothing at all`() = runTest {
        assertEquals(0, pendingMoments().flush())

        coVerify(exactly = 0) { client.send(any()) }
    }
}

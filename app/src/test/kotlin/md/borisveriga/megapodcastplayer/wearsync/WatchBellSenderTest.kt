package md.borisveriga.megapodcastplayer.wearsync

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearPaths
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [WatchBellSender].
 *
 * Everything worth pinning here is a way of failing quietly. A watch out of Bluetooth range is the
 * normal case, not an error, and must send nothing without complaint; a link that drops mid-send
 * must not propagate out into a player callback; and neither may go entirely unrecorded, because
 * nothing else in the app will ever notice a bell that did not buzz.
 */
class WatchBellSenderTest {

    private lateinit var nodeClient: NodeClient
    private lateinit var messageClient: MessageClient
    private lateinit var crashReporter: CrashReporter
    private lateinit var sender: WatchBellSender

    @Before
    fun setUp() {
        nodeClient = mockk(relaxed = true)
        messageClient = mockk(relaxed = true)
        crashReporter = mockk(relaxed = true)
        sender = WatchBellSender(nodeClient, messageClient, crashReporter)

        every { messageClient.sendMessage(any(), any(), any()) } returns Tasks.forResult(1)
        connectedNodes()
    }

    /** Stubs the Data Layer as having exactly [ids] connected. */
    private fun connectedNodes(vararg ids: String) {
        val nodes = ids.map { id ->
            mockk<Node>(relaxed = true).also { every { it.id } returns id }
        }
        every { nodeClient.connectedNodes } returns Tasks.forResult(nodes)
    }

    @Test
    fun `a connected watch is buzzed on the bell path`() = runTest {
        connectedNodes("watch-1")

        sender.buzz()

        // An empty payload: the path is the whole message.
        verify(exactly = 1) {
            messageClient.sendMessage("watch-1", WearPaths.BELL, match { it.isEmpty() })
        }
    }

    @Test
    fun `no watch in range sends nothing and reports nothing`() = runTest {
        connectedNodes()

        sender.buzz()

        verify(exactly = 0) { messageClient.sendMessage(any(), any(), any()) }
        // Out of range is the normal case, not a failure worth a crash report.
        verify(exactly = 0) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `two watches are both buzzed`() = runTest {
        connectedNodes("watch-1", "watch-2")

        sender.buzz()

        verify(exactly = 1) { messageClient.sendMessage("watch-1", WearPaths.BELL, any()) }
        verify(exactly = 1) { messageClient.sendMessage("watch-2", WearPaths.BELL, any()) }
    }

    @Test
    fun `one failed link does not stop the other`() = runTest {
        connectedNodes("watch-1", "watch-2")
        every {
            messageClient.sendMessage("watch-1", any(), any())
        } returns Tasks.forException(IllegalStateException("link dropped"))

        sender.buzz()

        // Two watches on one phone are two independent links.
        verify(exactly = 1) { messageClient.sendMessage("watch-2", WearPaths.BELL, any()) }
        verify(exactly = 1) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `a send that fails is recorded rather than thrown`() = runTest {
        connectedNodes("watch-1")
        every {
            messageClient.sendMessage(any(), any(), any())
        } returns Tasks.forException(IllegalStateException("link dropped"))

        // No exception: this runs inside a player callback on the service.
        sender.buzz()

        verify(exactly = 1) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `an unreadable node list is recorded rather than thrown`() = runTest {
        every { nodeClient.connectedNodes } returns
            Tasks.forException(IllegalStateException("Play Services unavailable"))

        sender.buzz()

        verify(exactly = 0) { messageClient.sendMessage(any(), any(), any()) }
        verify(exactly = 1) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `the bell path is under the app's own namespace`() {
        // The watch filters incoming messages by path prefix; a path outside it would never arrive.
        assertEquals(true, WearPaths.BELL.startsWith(WearPaths.PREFIX))
    }
}

package md.borisveriga.bpodcat.wearsync

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the check that stands between an arriving watch command and the phone's player.
 *
 * The rule itself is tested as a pure function; [WearSenderVerifier] is then tested for the part
 * that is easy to get wrong — what happens when the Data Layer will not answer.
 */
class WearSenderVerifierTest {

    private fun node(id: String): Node = mockk<Node>().also { every { it.id } returns id }

    private fun verifierFor(vararg connected: String): WearSenderVerifier {
        val nodeClient = mockk<NodeClient>()
        every { nodeClient.connectedNodes } returns Tasks.forResult(connected.map(::node))
        return WearSenderVerifier(nodeClient)
    }

    @Test
    fun `a connected node is a known sender`() {
        assertTrue(isKnownSender("watch-1", setOf("watch-1", "watch-2")))
    }

    @Test
    fun `a node that is not connected is not a known sender`() {
        assertFalse(isKnownSender("attacker", setOf("watch-1")))
    }

    @Test
    fun `an empty source node id is never a known sender`() {
        // What a malformed MessageEvent carries. It must not match an empty node list either.
        assertFalse(isKnownSender("", setOf("watch-1")))
        assertFalse(isKnownSender("", emptySet()))
    }

    @Test
    fun `no connected nodes means no known senders`() {
        assertFalse(isKnownSender("watch-1", emptySet()))
    }

    @Test
    fun `a command from the paired watch is trusted`() = runTest {
        assertTrue(verifierFor("watch-1").isTrusted("watch-1"))
    }

    @Test
    fun `a command from an unpaired node is refused`() = runTest {
        assertFalse(verifierFor("watch-1").isTrusted("somebody-else"))
    }

    @Test
    fun `the check fails closed when the node list cannot be read`() = runTest {
        // The case worth writing down: if this returned true on error, the whole check would be
        // worthless exactly when Play Services is in a state we cannot reason about.
        val nodeClient = mockk<NodeClient>()
        every { nodeClient.connectedNodes } returns
            Tasks.forException(ApiException(Status.RESULT_INTERNAL_ERROR))

        assertFalse(WearSenderVerifier(nodeClient).isTrusted("watch-1"))
    }
}

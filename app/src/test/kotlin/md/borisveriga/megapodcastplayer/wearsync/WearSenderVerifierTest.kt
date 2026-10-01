package md.borisveriga.megapodcastplayer.wearsync

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the check that stands between an arriving watch command and the phone's player.
 *
 * The rule itself is tested as a pure function; [WearSenderVerifier] is then tested for the parts
 * that are easy to get wrong — what happens when the Data Layer will not answer, and what the
 * cache in front of it is and is not allowed to answer for.
 */
class WearSenderVerifierTest {

    private fun node(id: String): Node = mockk<Node>().also { every { it.id } returns id }

    private fun verifierFor(vararg connected: String): WearSenderVerifier {
        val nodeClient = mockk<NodeClient>()
        every { nodeClient.connectedNodes } returns Tasks.forResult(connected.map(::node))
        return WearSenderVerifier(nodeClient)
    }

    /** A verifier whose clock the test moves, over a node client that counts its reads. */
    private class Rig(vararg connected: String) {
        var nowMs = 0L
        val nodeClient = mockk<NodeClient>()
        val verifier: WearSenderVerifier

        init {
            every { nodeClient.connectedNodes } returns Tasks.forResult(connected.map(::node))
            verifier = WearSenderVerifier(nodeClient) { nowMs }
        }

        private fun node(id: String): Node = mockk<Node>().also { every { it.id } returns id }
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

    // ---- The cache ------------------------------------------------------------------------------

    @Test
    fun `a recent node list vouches for a node it named`() {
        val known = KnownSenders(setOf("watch-1"), readAtElapsedMs = 1_000L)

        assertTrue(vouchedFor("watch-1", known, nowElapsedMs = 1_000L + KNOWN_SENDERS_TTL_MS - 1L))
    }

    @Test
    fun `a node list past its time vouches for nobody`() {
        val known = KnownSenders(setOf("watch-1"), readAtElapsedMs = 1_000L)

        assertFalse(vouchedFor("watch-1", known, nowElapsedMs = 1_000L + KNOWN_SENDERS_TTL_MS))
    }

    /** The cache only ever says yes: a stranger, or an empty id, is asked about live. */
    @Test
    fun `a recent node list never refuses from memory`() {
        val known = KnownSenders(setOf("watch-1"), readAtElapsedMs = 1_000L)

        assertFalse(vouchedFor("stranger", known, nowElapsedMs = 1_000L))
        assertFalse(vouchedFor("", known, nowElapsedMs = 1_000L))
        assertFalse(vouchedFor("watch-1", known = null, nowElapsedMs = 1_000L))
    }

    /** A turn of the bezel is a burst of commands; one Play Services call should cover it. */
    @Test
    fun `a second command from the same watch does not read the node list again`() = runTest {
        val rig = Rig("watch-1")

        assertTrue(rig.verifier.isTrusted("watch-1"))
        rig.nowMs = KNOWN_SENDERS_TTL_MS - 1L
        assertTrue(rig.verifier.isTrusted("watch-1"))

        verify(exactly = 1) { rig.nodeClient.connectedNodes }
    }

    @Test
    fun `a command after the window reads the node list afresh`() = runTest {
        val rig = Rig("watch-1")

        assertTrue(rig.verifier.isTrusted("watch-1"))
        rig.nowMs = KNOWN_SENDERS_TTL_MS
        assertTrue(rig.verifier.isTrusted("watch-1"))

        verify(exactly = 2) { rig.nodeClient.connectedNodes }
    }

    /**
     * The half that keeps the cache honest: a node the last list did not name is checked live, so
     * the cache can make the check cheaper for the paired watch and never looser for anyone else.
     */
    @Test
    fun `a command from an unknown node is checked live even within the window`() = runTest {
        val rig = Rig("watch-1")

        assertTrue(rig.verifier.isTrusted("watch-1"))
        assertFalse(rig.verifier.isTrusted("stranger"))

        verify(exactly = 2) { rig.nodeClient.connectedNodes }
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

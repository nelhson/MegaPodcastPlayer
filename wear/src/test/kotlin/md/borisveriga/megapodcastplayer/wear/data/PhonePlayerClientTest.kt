package md.borisveriga.megapodcastplayer.wear.data

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests what a command costs on the way out of the watch.
 *
 * A tap used to be two trips to Play Services — a capability lookup, then the message — and the
 * first answered a question the link check already asks. These pin the other shape: the lookup's
 * last answer is reused, and asked for again only when there is none or it stops working.
 *
 * Under Robolectric because the client builds a `Uri` for the data item it watches, and a plain JVM
 * has no `Uri`. The Play Services clients themselves are stand-ins; `Tasks.forResult` is real.
 */
@RunWith(RobolectricTestRunner::class)
class PhonePlayerClientTest {

    private val dataClient = mockk<DataClient>(relaxed = true)
    private val messageClient = mockk<MessageClient>()
    private val capabilityClient = mockk<CapabilityClient>()
    private val nodeClient = mockk<NodeClient>()

    private val client = PhonePlayerClient(dataClient, messageClient, capabilityClient, nodeClient)

    @Test
    fun `the first command looks the phone up and the next one does not`() = runTest {
        phoneAdvertises("phone")
        accepts("phone")

        assertTrue(client.send(WearCommand.TogglePlayPause))
        assertTrue(client.send(WearCommand.SkipForward))

        verify(exactly = 1) { capabilityClient.getCapability(any(), any()) }
        verify(exactly = 2) { messageClient.sendMessage("phone", WearPaths.COMMAND, any()) }
    }

    /** The link check asks the same question, so a command after it need not ask again. */
    @Test
    fun `a link check answers the lookup a command would have made`() = runTest {
        phoneAdvertises("phone")
        accepts("phone")
        every { capabilityClient.addListener(any(), any<String>()) } returns Tasks.forResult(null)
        every { capabilityClient.removeListener(any(), any<String>()) } returns Tasks.forResult(true)

        assertEquals(PhoneLink.CONNECTED, client.phoneLink.first())
        assertTrue(client.send(WearCommand.TogglePlayPause))

        verify(exactly = 1) { capabilityClient.getCapability(any(), any()) }
    }

    /**
     * The other half of remembering: a phone that re-pairs comes back under a new node id, and the
     * old one stops taking messages. One failed message is the most the memory may cost.
     */
    @Test
    fun `a phone that stopped taking commands is looked up again`() = runTest {
        phoneAdvertises("old")
        accepts("old")
        assertTrue(client.send(WearCommand.TogglePlayPause))

        refuses("old")
        phoneAdvertises("new")
        accepts("new")

        assertTrue(client.send(WearCommand.SkipForward))
        verify(exactly = 2) { capabilityClient.getCapability(any(), any()) }
        verify(exactly = 1) { messageClient.sendMessage("new", WearPaths.COMMAND, any()) }
    }

    @Test
    fun `no phone means nothing is sent and the command fails`() = runTest {
        phoneAdvertises()

        assertFalse(client.send(WearCommand.TogglePlayPause))

        verify(exactly = 0) { messageClient.sendMessage(any(), any(), any()) }
    }

    /** A lookup that finds nothing forgets the phone: a gone phone is not addressed from memory. */
    @Test
    fun `a phone the lookup no longer finds is forgotten`() = runTest {
        phoneAdvertises("phone")
        accepts("phone")
        assertTrue(client.send(WearCommand.TogglePlayPause))

        phoneAdvertises()
        every { capabilityClient.addListener(any(), any<String>()) } returns Tasks.forResult(null)
        every { capabilityClient.removeListener(any(), any<String>()) } returns Tasks.forResult(true)
        every { nodeClient.connectedNodes } returns Tasks.forResult(emptyList())
        assertEquals(PhoneLink.DISCONNECTED, client.phoneLink.first())

        // Sent to nobody: not to the remembered phone, which the link check has just unlearnt.
        assertFalse(client.send(WearCommand.SkipForward))
        verify(exactly = 1) { messageClient.sendMessage(any(), any(), any()) }
    }

    /** Makes the capability lookup name these nodes as the phones running the app. */
    private fun phoneAdvertises(vararg nodeIds: String) {
        every {
            capabilityClient.getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        } returns capability(*nodeIds)
    }

    /** Makes [nodeId] take every message. */
    private fun accepts(nodeId: String) {
        every { messageClient.sendMessage(nodeId, WearPaths.COMMAND, any()) } returns Tasks.forResult(1)
    }

    /** Makes [nodeId] refuse every message, as a node that has gone does. */
    private fun refuses(nodeId: String) {
        every { messageClient.sendMessage(nodeId, WearPaths.COMMAND, any()) } returns
            Tasks.forException(ApiException(Status.RESULT_INTERNAL_ERROR))
    }

    private fun capability(vararg nodeIds: String): Task<CapabilityInfo> {
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns nodeIds.map(::node).toSet()
        return Tasks.forResult(info)
    }

    private fun node(id: String): Node = mockk<Node>().also { every { it.id } returns id }
}

package md.borisveriga.bpodcat.wearsync

import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.NodeClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/** Tag for the one thing this file logs: a command from a node this phone is not paired with. */
private const val TAG = "WearSenderVerifier"

/**
 * Whether a command that arrived from [sourceNodeId] came from a node this device is paired with.
 *
 * Split out from [WearSenderVerifier] as a pure function so the rule itself can be tested without a
 * Play Services stand-in. The rule is deliberately plain — membership, plus a rejection of the empty
 * id that a malformed [com.google.android.gms.wearable.MessageEvent] would carry.
 *
 * @param sourceNodeId the id the message claims to come from.
 * @param connectedNodeIds ids of the nodes currently connected to this device.
 */
internal fun isKnownSender(sourceNodeId: String, connectedNodeIds: Set<String>): Boolean =
    sourceNodeId.isNotEmpty() && sourceNodeId in connectedNodeIds

/**
 * Decides whether a watch command may be executed.
 *
 * Play Services already restricts message delivery to peers that share this app's package name and
 * signing certificate, so with a real release key (see `configureSharedSigning`) this is defence in
 * depth rather than the only lock. It is worth having anyway: it is the half of the check that does
 * not depend on the signing key never leaking, and it costs one cached Play Services call per
 * command — commands arrive at the rate a thumb can press buttons.
 *
 * ## Failing closed
 *
 * If the node list cannot be read, the command is dropped. The alternative — executing on the
 * assumption that the sender is fine — would make the check worthless exactly when Play Services is
 * in a state we cannot reason about. The cost of a false rejection is small and self-correcting: the
 * watch's next button press is a new message, and by then the node list has normally resolved.
 *
 * @property nodeClient the Data Layer's view of which nodes are connected. Injected rather than
 *   obtained statically so this class can be unit-tested.
 */
@Singleton
class WearSenderVerifier @Inject constructor(
    private val nodeClient: NodeClient,
) {

    /**
     * @param sourceNodeId `MessageEvent.getSourceNodeId()` of the command that arrived.
     * @return true when the command may be executed.
     */
    suspend fun isTrusted(sourceNodeId: String): Boolean {
        val connected = connectedNodeIds() ?: return false
        val trusted = isKnownSender(sourceNodeId, connected)
        if (!trusted) {
            // The id is logged because it is a Play Services node id, not user data, and knowing
            // which node was refused is the only way to tell a real rejection from a race.
            Log.w(TAG, "Ignoring a command from unknown node $sourceNodeId")
        }
        return trusted
    }

    /**
     * Reads the connected nodes.
     *
     * @return their ids, or null when the Data Layer could not be asked at all — which the caller
     *   treats as "not trusted" rather than as an empty set, so the two cases stay distinguishable
     *   here even though they lead to the same decision.
     */
    private suspend fun connectedNodeIds(): Set<String>? = try {
        nodeClient.connectedNodes.await().mapTo(mutableSetOf()) { it.id }
    } catch (e: ApiException) {
        // No Play Services, no Wear OS companion, or the call was rejected. Not worth a crash in
        // what is a Play Services callback on the phone's main process.
        Log.w(TAG, "Could not read the connected nodes; dropping the command", e)
        null
    }
}

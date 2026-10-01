package md.borisveriga.megapodcastplayer.wearsync

import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.NodeClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/** Tag for the one thing this file logs: a command from a node this phone is not paired with. */
private const val TAG = "WearSenderVerifier"

/**
 * How long a node list, once read, keeps answering for the nodes it named.
 *
 * A watch command arrived, and it used to be followed by a Play Services call to read the connected
 * nodes before anything happened — on every command, including each step of a volume turn. The
 * list changes when a watch is paired or walks out of range, which is never twice in a few
 * seconds, so a node the list named this recently is still the paired watch. The window is short
 * because the check is a security check, and what it costs to be wrong is a command executed from a
 * node that *was* paired a few seconds ago; that is a node this phone trusted a moment before, not
 * a stranger.
 */
internal const val KNOWN_SENDERS_TTL_MS = 10_000L

/**
 * A node list as of when it was read.
 *
 * @property nodeIds the nodes connected at [readAtElapsedMs].
 * @property readAtElapsedMs the phone's elapsed-realtime clock when Play Services answered.
 */
internal data class KnownSenders(
    val nodeIds: Set<String>,
    val readAtElapsedMs: Long,
)

/**
 * Whether a recent node list can vouch for [sourceNodeId] without asking Play Services again.
 *
 * The rule the cache follows, as a pure function for the same reason [isKnownSender] is one. It
 * only ever says *yes*: a node the last list did not name is not refused from memory — the list is
 * read again, because the most likely reason it is missing is that it was paired since. The cache
 * therefore never makes the check stricter or looser than the live read, only cheaper for the
 * sender it has already seen.
 *
 * @param sourceNodeId the id the message claims to come from.
 * @param known the last node list read, or null if none has been.
 * @param nowElapsedMs the phone's elapsed-realtime clock.
 */
internal fun vouchedFor(sourceNodeId: String, known: KnownSenders?, nowElapsedMs: Long): Boolean =
    known != null &&
        nowElapsedMs - known.readAtElapsedMs < KNOWN_SENDERS_TTL_MS &&
        isKnownSender(sourceNodeId, known.nodeIds)

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
 * not depend on the signing key never leaking, and it costs one Play Services call per
 * [KNOWN_SENDERS_TTL_MS] of commands from the same watch — commands arrive at the rate a thumb can
 * press buttons, and a turn of the bezel is a burst of them.
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
 * @property clock the phone's elapsed-realtime clock, for ageing the last node list; a parameter so
 *   that a test can move it. The app hands in [SystemClock.elapsedRealtime].
 */
@Singleton
class WearSenderVerifier internal constructor(
    private val nodeClient: NodeClient,
    private val clock: () -> Long,
) {

    /** The constructor Hilt uses: the real clock. */
    @Inject
    constructor(nodeClient: NodeClient) : this(nodeClient, SystemClock::elapsedRealtime)

    /**
     * The last node list read, for [vouchedFor]. Replaced whole on every read, never mutated, so a
     * command on another thread sees either the old list or the new one.
     */
    @Volatile
    private var known: KnownSenders? = null

    /**
     * @param sourceNodeId `MessageEvent.getSourceNodeId()` of the command that arrived.
     * @return true when the command may be executed.
     */
    suspend fun isTrusted(sourceNodeId: String): Boolean {
        if (vouchedFor(sourceNodeId, known, clock())) return true

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
     * Reads the connected nodes, and remembers them for the next command.
     *
     * @return their ids, or null when the Data Layer could not be asked at all — which the caller
     *   treats as "not trusted" rather than as an empty set, so the two cases stay distinguishable
     *   here even though they lead to the same decision. A failed read remembers nothing: it is not
     *   a list, and the last good one must not go on vouching past its time.
     */
    private suspend fun connectedNodeIds(): Set<String>? = try {
        nodeClient.connectedNodes.await()
            .mapTo(mutableSetOf()) { it.id }
            .also { known = KnownSenders(nodeIds = it, readAtElapsedMs = clock()) }
    } catch (e: ApiException) {
        // No Play Services, no Wear OS companion, or the call was rejected. Not worth a crash in
        // what is a Play Services callback on the phone's main process.
        Log.w(TAG, "Could not read the connected nodes; dropping the command", e)
        null
    }
}

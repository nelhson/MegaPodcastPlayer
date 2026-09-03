package md.borisveriga.bpodcat.wear.data

import android.net.Uri
import android.os.SystemClock
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.core.wearprotocol.WearCommand
import md.borisveriga.bpodcat.core.wearprotocol.WearMessages
import md.borisveriga.bpodcat.core.wearprotocol.WearPaths

/**
 * A snapshot from the phone, stamped with the moment the watch received it.
 *
 * The stamp is the watch's own [SystemClock.elapsedRealtime], never the phone's clock: the two
 * devices' wall clocks are independent, but the *interval* since arrival is exactly what
 * [NowPlayingSnapshot.positionAfter] needs, and that is measurable locally.
 *
 * @property snapshot what the phone said.
 * @property receivedAtElapsedMs the watch's elapsed-realtime clock when it arrived.
 */
data class ReceivedSnapshot(
    val snapshot: NowPlayingSnapshot,
    val receivedAtElapsedMs: Long,
)

/**
 * The watch's connection to the phone's player.
 *
 * Everything the watch app does goes through here: it reads the phone's state from a Data Layer
 * data item and writes the user's intent back as messages. There is no local playback, no cache and
 * no retry queue — a command that cannot be delivered is reported as failed, and the screen says so
 * rather than leaving the user pressing a dead button.
 *
 * @property dataClient carries the phone's published state.
 * @property messageClient carries commands to the phone.
 * @property capabilityClient identifies which connected node actually runs BPodcat.
 * @property nodeClient tells a missing app apart from a missing phone.
 */
@Singleton
class PhonePlayerClient @Inject constructor(
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
    private val capabilityClient: CapabilityClient,
    private val nodeClient: NodeClient,
) {

    /**
     * The data item to watch.
     *
     * The wildcard authority means "from any node": the watch is paired with one phone, and
     * hard-coding its node id would break the moment the user re-pairs.
     */
    private val nowPlayingUri: Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .authority(ANY_NODE)
        .path(WearPaths.NOW_PLAYING)
        .build()

    /**
     * The phone's playback state, as it changes.
     *
     * Emits null first when the Data Layer holds nothing yet, so that a collector combining this
     * with anything else still gets a first frame — without it the screen would stay blank forever
     * on a watch that has never heard from the phone.
     *
     * The cached item is read *after* the listener is registered. The other order leaves a window in
     * which an update lands between the read and the registration and is lost; this way the worst
     * case is the same snapshot delivered twice, which costs nothing.
     */
    val snapshots: Flow<ReceivedSnapshot?> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED) {
                    // The buffer is recycled as soon as this returns, so decoding must happen now.
                    decode(event.dataItem)?.let(::trySend)
                }
            }
        }
        dataClient.addListener(listener, nowPlayingUri, DataClient.FILTER_LITERAL)

        send(readCachedSnapshot())

        awaitClose { dataClient.removeListener(listener) }
    }

    /**
     * Whether the phone can be reached, as it changes.
     *
     * Both listened for and polled. The capability listener fires promptly when the phone app
     * appears or disappears, but not in every case that matters — a phone carried out of Bluetooth
     * range does not always announce itself — and a remote control that keeps claiming to be
     * connected while nothing works is worse than one that takes a poll interval to notice.
     *
     * The poll interval backs off. This flow is collected for as long as a screen is up, and on a
     * watch a fixed ten-second wake-up is a measurable battery cost for a check that almost never
     * changes its answer. The first [EAGER_POLL_COUNT] polls run at [EAGER_POLL_INTERVAL_MS], which
     * covers the case that matters — the user has just raised their wrist and the listener has not
     * caught up yet — and every poll after that runs at [IDLE_POLL_INTERVAL_MS].
     */
    val phoneLink: Flow<PhoneLink> = callbackFlow {
        val listener = CapabilityClient.OnCapabilityChangedListener {
            launch { send(currentLink()) }
        }
        capabilityClient.addListener(listener, WearPaths.PHONE_CAPABILITY)

        val poller = launch {
            var completedPolls = 0
            while (isActive) {
                send(currentLink())
                completedPolls++
                delay(linkPollDelayMs(completedPolls))
            }
        }

        awaitClose {
            poller.cancel()
            capabilityClient.removeListener(listener, WearPaths.PHONE_CAPABILITY)
        }
    }

    /**
     * Asks the phone to do something.
     *
     * @param command what to ask for.
     * @return true if at least one node accepted the message.
     */
    suspend fun send(command: WearCommand): Boolean {
        val nodeIds = phoneNodeIds()
        if (nodeIds.isEmpty()) return false

        val payload = WearMessages.encodeCommand(command)
        // Delivered to every candidate node: a watch paired with two phones should reach whichever
        // one is actually playing, and the other one ignores a command about an episode it has not
        // loaded anyway.
        return nodeIds.count { nodeId ->
            suspendRunCatching {
                messageClient.sendMessage(nodeId, WearPaths.COMMAND, payload).await()
            }.isSuccess
        } > 0
    }

    /**
     * The last thing the phone published, read once.
     *
     * For the surfaces that draw a picture and stop — the tile and the complication — rather than
     * collecting [snapshots] for as long as a screen is up. Both are asked for a layout, build one
     * and are torn down, so a flow would be subscribed and disposed in the same breath.
     *
     * @return the snapshot the Data Layer holds, or null if it holds none or it is unreadable.
     */
    suspend fun cachedSnapshot(): NowPlayingSnapshot? = readCachedSnapshot()?.snapshot

    /** Classifies the link; see [PhoneLink] for why the three failures are kept apart. */
    private suspend fun currentLink(): PhoneLink {
        if (capablePhoneNodeIds().isNotEmpty()) return PhoneLink.CONNECTED

        val connected = suspendRunCatching { nodeClient.connectedNodes.await() }
            .getOrNull()
            .orEmpty()
        return if (connected.isEmpty()) PhoneLink.DISCONNECTED else PhoneLink.APP_NOT_INSTALLED
    }

    /**
     * Node ids to send a command to.
     *
     * Prefers nodes advertising BPodcat's capability. Falls back to every connected node, which
     * covers a phone running a build old enough not to advertise it — sending to a node that cannot
     * handle the path is harmless, and beats refusing to work at all.
     */
    private suspend fun phoneNodeIds(): List<String> = capablePhoneNodeIds().ifEmpty {
        suspendRunCatching { nodeClient.connectedNodes.await() }.getOrNull().orEmpty().map { it.id }
    }

    /** Ids of reachable nodes that advertise the phone app's capability. */
    private suspend fun capablePhoneNodeIds(): List<String> = suspendRunCatching {
        capabilityClient
            .getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
            .map { it.id }
    }.getOrNull().orEmpty()

    /**
     * Reads the snapshot the Data Layer already holds.
     *
     * This is what makes the app open showing the right episode instead of a spinner: the item was
     * replicated to the watch when the phone published it, and stays readable with the phone
     * asleep, out of range or switched off.
     */
    private suspend fun readCachedSnapshot(): ReceivedSnapshot? = suspendRunCatching {
        val buffer = dataClient.getDataItems(nowPlayingUri).await()
        try {
            buffer.firstNotNullOfOrNull(::decode)
        } finally {
            buffer.release()
        }
    }.getOrNull()

    /**
     * Turns a data item into a stamped snapshot.
     *
     * @return null when the item carries no payload, or one this build cannot read — which is how a
     *   watch survives meeting a phone running a newer version of the app.
     */
    private fun decode(item: DataItem): ReceivedSnapshot? {
        val bytes = DataMapItem.fromDataItem(item).dataMap.getByteArray(WearPaths.PAYLOAD_KEY)
            ?: return null
        val snapshot = WearMessages.decodeSnapshot(bytes) ?: return null
        return ReceivedSnapshot(snapshot, SystemClock.elapsedRealtime())
    }

    private companion object {
        /** Data Layer wildcard authority: match the item whichever node published it. */
        const val ANY_NODE = "*"
    }
}

/**
 * How often the link is re-checked just after a screen starts collecting.
 *
 * Short, because this is the window in which the capability listener is most likely to be behind
 * reality: the watch has just woken and Play Services may not have re-established the connection.
 */
internal const val EAGER_POLL_INTERVAL_MS = 10_000L

/** How many polls run at [EAGER_POLL_INTERVAL_MS] before backing off — the first minute of a screen. */
internal const val EAGER_POLL_COUNT = 6

/**
 * How often the link is re-checked thereafter.
 *
 * The capability listener covers every transition it can see; this only exists to catch the one it
 * cannot — a phone carried out of range without announcing it — and once a minute is often enough
 * for that on a device where every wake-up costs battery.
 */
internal const val IDLE_POLL_INTERVAL_MS = 60_000L

/**
 * How long to wait before re-checking the phone link.
 *
 * Extracted from [PhonePlayerClient.phoneLink] so the policy can be asserted without standing up
 * four Play Services clients. The flow is collected for as long as a screen is up, so a fixed
 * ten-second wake-up would be a permanent battery cost for a check that almost never changes its
 * answer; the eager phase exists only to cover the moment the user raises their wrist and the
 * capability listener has not caught up yet.
 *
 * @param completedPolls how many polls this collection has already performed.
 * @return the delay before the next one.
 */
internal fun linkPollDelayMs(completedPolls: Int): Long =
    if (completedPolls < EAGER_POLL_COUNT) EAGER_POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS

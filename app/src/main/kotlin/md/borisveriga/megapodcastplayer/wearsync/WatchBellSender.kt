package md.borisveriga.megapodcastplayer.wearsync

import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearPaths

/**
 * Buzzes the watch when the end-of-episode bell rings.
 *
 * The phone's notification is the bell proper; this is the half that reaches a wrist under a duvet,
 * where a phone on a bedside table may not. It is the only message the phone initiates — everything
 * else it sends the watch is state, published as a data item and read when the watch gets round to
 * it, which is precisely the wrong shape for something that has to arrive now and arrive once.
 *
 * @property nodeClient the Data Layer's view of which watches are connected.
 * @property messageClient how the buzz is sent.
 * @property crashReporter where a failed send goes, since nothing else will notice one.
 */
@Singleton
class WatchBellSender @Inject constructor(
    private val nodeClient: NodeClient,
    private val messageClient: MessageClient,
    private val crashReporter: CrashReporter,
) {

    /**
     * Buzzes every connected watch.
     *
     * A watch out of Bluetooth range is not an error and not a special case: it is simply absent
     * from the node list, so the loop below sends nothing and this returns. The bell has already
     * sounded on the phone by the time this runs, so there is nothing to retry and nobody to tell.
     *
     * Each node is sent separately rather than the whole thing being abandoned on the first
     * failure, because two watches paired to one phone are two independent links.
     */
    suspend fun buzz() {
        val nodes = suspendRunCatching { nodeClient.connectedNodes.await() }
            .onFailure { failure ->
                crashReporter.recordNonFatal("Could not list nodes to buzz the bell", failure)
            }
            .getOrNull()
            .orEmpty()

        for (node in nodes) {
            suspendRunCatching {
                // An empty payload: the path is the whole message. See WearPaths.BELL.
                messageClient.sendMessage(node.id, WearPaths.BELL, EMPTY_PAYLOAD).await()
            }.onFailure { failure ->
                crashReporter.recordNonFatal("Could not buzz the bell on the watch", failure)
            }
        }
    }

    private companion object {
        /** The bell carries no data; allocated once rather than per send. */
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}

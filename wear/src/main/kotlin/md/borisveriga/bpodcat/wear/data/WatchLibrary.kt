package md.borisveriga.bpodcat.wear.data

import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.wearprotocol.OfflineLibrary
import md.borisveriga.bpodcat.core.wearprotocol.WearMessages
import md.borisveriga.bpodcat.core.wearprotocol.WearPaths

/**
 * What the phone has downloaded, as the watch sees it.
 *
 * The mirror image of [PhonePlayerClient]'s snapshot, on its own data item and its own clock: this
 * list changes a few times a week where playback state changes several times a minute, and reading
 * one to learn the other would spend the watch's Bluetooth budget on the wrong thing.
 *
 * Like the snapshot, the Data Layer keeps the last copy, so the list is there with the phone asleep
 * — which matters, because the moment somebody wants it is the moment before they leave the phone
 * behind.
 *
 * @property dataClient carries the list the phone published.
 */
@Singleton
class WatchLibrary @Inject constructor(
    private val dataClient: DataClient,
) {

    /** The published list, from whichever node published it; see [PhonePlayerClient] on the wildcard. */
    private val libraryUri: Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .authority(ANY_NODE)
        .path(WearPaths.OFFLINE_LIBRARY)
        .build()

    /**
     * The phone's offline library, as it changes.
     *
     * Emits an empty library first when the Data Layer holds nothing, so a collector combining this
     * with anything else gets a first frame rather than waiting on a phone that may have nothing to
     * say.
     */
    val library: Flow<OfflineLibrary> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED) {
                    // The buffer is recycled as soon as this returns, so decoding happens now.
                    decode(event.dataItem)?.let(::trySend)
                }
            }
        }
        dataClient.addListener(listener, libraryUri, DataClient.FILTER_LITERAL)

        // Read after registering: the other order can lose an update that lands in between, where
        // this order can at worst deliver the same list twice.
        send(cached())

        awaitClose { dataClient.removeListener(listener) }
    }

    /**
     * The list the Data Layer already holds.
     *
     * Also used outside the app: the service that receives an episode's audio needs the title and
     * the expected size that went with the offer, and it has no screen collecting [library].
     *
     * @return what the phone last published, or an empty library if it has published nothing.
     */
    suspend fun cached(): OfflineLibrary = suspendRunCatching {
        val buffer = dataClient.getDataItems(libraryUri).await()
        try {
            buffer.firstNotNullOfOrNull(::decode)
        } finally {
            buffer.release()
        }
    }.getOrNull() ?: OfflineLibrary()

    /**
     * Turns a data item into a library.
     *
     * @return null when the item carries no payload, or one this build cannot read — which is how a
     *   watch survives meeting a phone running a newer version of the app.
     */
    private fun decode(item: DataItem): OfflineLibrary? {
        val bytes = DataMapItem.fromDataItem(item).dataMap.getByteArray(WearPaths.PAYLOAD_KEY)
            ?: return null
        return WearMessages.decodeLibrary(bytes)
    }

    private companion object {
        /** Data Layer wildcard authority: match the item whichever node published it. */
        const val ANY_NODE = "*"
    }
}

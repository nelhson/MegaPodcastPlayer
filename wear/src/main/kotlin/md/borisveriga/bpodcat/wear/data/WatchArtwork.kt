package md.borisveriga.bpodcat.wear.data

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.gms.wearable.Asset
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.wearprotocol.WearPaths

/**
 * The episode artwork the phone published, decoded for the screen.
 *
 * Kept apart from [PhonePlayerClient] because the two read the same data item in different ways.
 * The snapshot is a byte array that must be decoded inside the listener callback, before the event
 * buffer is recycled; an asset is a *reference* which is resolved afterwards with a suspending call,
 * so it cannot share that path.
 *
 * Nothing here is load-bearing. Every failure — no artwork published, a phone too old to send one,
 * an unreadable asset — becomes null, and the screen draws the plain header it drew before.
 *
 * @property dataClient the Data Layer, which both holds the asset and resolves it.
 */
@Singleton
class WatchArtwork @Inject constructor(
    private val dataClient: DataClient,
) {

    /** The published now-playing item, from whichever node published it. */
    private val nowPlayingUri: Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .authority(ANY_NODE)
        .path(WearPaths.NOW_PLAYING)
        .build()

    /**
     * The most recently decoded artwork, keyed by the asset's content digest.
     *
     * The phone republishes on every seek, speed change and queue edit, and attaches the same asset
     * each time; without this, each of those would re-read and re-decode an identical image. The
     * digest is the Data Layer's own content hash, so it changes exactly when the image does.
     */
    @Volatile
    private var cached: Pair<String, ImageBitmap>? = null

    /**
     * Artwork for whatever the phone has loaded, as it changes.
     *
     * Emits null first so a collector combining this with anything else gets a first frame
     * immediately, rather than waiting for a phone that may have nothing to say.
     */
    val artwork: Flow<ImageBitmap?> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED) {
                    // The asset reference has to be taken now, while the buffer is alive; resolving
                    // it is what happens later. Sent with trySend rather than from a launched
                    // coroutine, so two updates in quick succession cannot arrive out of order.
                    trySend(assetFrom(event.dataItem))
                }
            }
        }
        dataClient.addListener(listener, nowPlayingUri, DataClient.FILTER_LITERAL)

        // Read after registering, so an update landing in between is duplicated rather than lost.
        send(cachedAsset())

        awaitClose { dataClient.removeListener(listener) }
    }
        // Compared by digest rather than by identity: two publishes of the same image produce two
        // Asset instances, and re-decoding on each would undo the point of the cache below. Two
        // nulls compare equal too, so a phone playing an episode with no artwork does not push a
        // fresh null through the screen state on every seek.
        .distinctUntilChanged { old, new -> old?.digest == new?.digest }
        .map { decode(it) }

    /**
     * Resolves an asset into a bitmap.
     *
     * @param asset the reference published by the phone, or null when it sent no artwork.
     * @return the decoded image, or null if there was none or it could not be read.
     */
    private suspend fun decode(asset: Asset?): ImageBitmap? {
        val digest = asset?.digest ?: return null
        cached?.takeIf { it.first == digest }?.let { return it.second }

        return suspendRunCatching {
            val response = dataClient.getFdForAsset(asset).await()
            // Released rather than closed: GetFdForAssetResponse is a Play Services `Releasable`,
            // not a Closeable, so `use` does not apply. Leaking it leaks a file descriptor.
            try {
                response.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } finally {
                response.release()
            }
        }.getOrNull()?.also { cached = digest to it }
    }

    /** Reads the asset the Data Layer already holds, so the screen has artwork before any update. */
    private suspend fun cachedAsset(): Asset? = suspendRunCatching {
        val buffer = dataClient.getDataItems(nowPlayingUri).await()
        try {
            buffer.firstNotNullOfOrNull(::assetFrom)
        } finally {
            buffer.release()
        }
    }.getOrNull()

    /**
     * Pulls the artwork asset out of a data item.
     *
     * @return null when the phone published no artwork, which is normal rather than an error: an
     *   episode may have none, and a phone running an older build never sends any.
     */
    private fun assetFrom(item: DataItem): Asset? =
        DataMapItem.fromDataItem(item).dataMap.getAsset(WearPaths.ARTWORK_KEY)

    private companion object {
        /** Data Layer wildcard authority: match the item whichever node published it. */
        const val ANY_NODE = "*"
    }
}

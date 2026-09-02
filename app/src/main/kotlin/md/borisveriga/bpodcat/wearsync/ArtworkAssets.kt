package md.borisveriga.bpodcat.wearsync

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import com.google.android.gms.wearable.Asset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching

/**
 * The longest edge, in pixels, of the artwork sent to the watch.
 *
 * Sized for the screen it lands on rather than the one it came from: the largest Wear display is
 * around 450 px across, and the watch draws this behind text as a wash, not as a sharp cover. The
 * phone's own copy is typically 1400 px square, which would be roughly fifty times the bytes for no
 * visible difference on a wrist.
 */
private const val ARTWORK_SIZE_PX = 200

/**
 * JPEG quality for the encoded artwork.
 *
 * Cover art is photographic and already lossy; 85 is the point past which the file grows faster
 * than the image improves. JPEG rather than PNG because these images are never transparent, and PNG
 * would roughly triple the payload.
 */
private const val ARTWORK_QUALITY = 85

/**
 * How long the artwork may take before the snapshot goes out without it.
 *
 * The publish waits on this, so an unbounded fetch would mean a play/pause the watch does not see
 * until a slow image finishes downloading — the remote control feeling broken because a decoration
 * was slow. Two seconds covers a disk-cache hit and a quick network one; past that the snapshot is
 * worth more than the picture, and the next publish will carry the artwork once it is cached.
 */
private const val ARTWORK_TIMEOUT_MS = 2_000L

/**
 * The last artwork encoded, kept so an unchanged image is not re-encoded.
 *
 * @property url the artwork this was built from, and the cache key.
 * @property asset the encoded bytes, ready to attach to a data item.
 */
private data class CachedArtwork(
    val url: String,
    val asset: Asset,
)

/**
 * Turns an episode's artwork URL into a Data Layer [Asset] for the watch.
 *
 * The watch cannot fetch artwork for itself: its only route to the network is a proxy through the
 * phone, so every image would cross the Bluetooth link at full size. The phone already holds these
 * images in Coil's disk cache, so it downscales one to [ARTWORK_SIZE_PX] and sends the bytes
 * instead. See [md.borisveriga.bpodcat.core.wearprotocol.WearPaths.ARTWORK_KEY].
 *
 * Results are cached one deep. [NowPlayingPublisher] republishes on every seek, queue change and
 * speed change, and decoding plus recompressing a bitmap on each of those would be pure waste —
 * whereas the artwork itself only changes when the episode does, so one entry hits almost always.
 *
 * Every failure yields null. Artwork is decoration, and an image that will not load must never stop
 * the watch being told what is playing.
 *
 * @property context used only to build requests; Coil takes nothing else from it here.
 * @property imageLoader the app's shared Coil loader, which already has the disk cache and the
 *   app's `OkHttpClient` behind it.
 */
@Singleton
internal class ArtworkAssets @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
) {

    /**
     * Guards [cached].
     *
     * The publisher runs on the application scope, which is backed by a thread pool, so the
     * collector coroutine and a `RequestState` reply can arrive here on different threads. The lock
     * also collapses a concurrent pair of requests for the same URL into one decode.
     */
    private val mutex = Mutex()

    private var cached: CachedArtwork? = null

    /**
     * Encodes artwork for the watch.
     *
     * @param url the artwork to send, or null when nothing is loaded.
     * @return the asset to attach, or null when there is no artwork or it could not be loaded.
     */
    suspend fun assetFor(url: String?): Asset? {
        if (url.isNullOrBlank()) return null

        return mutex.withLock {
            cached?.takeIf { it.url == url }?.asset ?: encode(url)?.also {
                cached = CachedArtwork(url, it)
            }
        }
    }

    /**
     * Loads, downscales and compresses one image.
     *
     * @param url the artwork to load.
     * @return the encoded asset, or null if any step failed.
     */
    private suspend fun encode(url: String): Asset? = withTimeoutOrNull(ARTWORK_TIMEOUT_MS) {
        suspendRunCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(ARTWORK_SIZE_PX)
                .scale(Scale.FIT)
                // A hardware bitmap keeps its pixels on the GPU where this process cannot read
                // them, and compress() needs to read them.
                .allowHardware(false)
                .build()

            val image = (imageLoader.execute(request) as? SuccessResult)?.image
                ?: return@suspendRunCatching null
            val bitmap = image.toBitmap(image.width, image.height)

            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_QUALITY, out)) {
                    return@suspendRunCatching null
                }
                Asset.createFromBytes(out.toByteArray())
            }
        }.getOrNull()
    }
}

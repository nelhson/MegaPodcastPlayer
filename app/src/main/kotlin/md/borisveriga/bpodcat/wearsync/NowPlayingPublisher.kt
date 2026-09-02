package md.borisveriga.bpodcat.wearsync

import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import md.borisveriga.bpodcat.core.common.di.ApplicationScope
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.media.PlaybackConnection
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.core.wearprotocol.WearMessages
import md.borisveriga.bpodcat.core.wearprotocol.WearPaths

/**
 * A snapshot that has been sent to the watch, and when.
 *
 * @property snapshot what was published.
 * @property atMs the phone's wall clock at the moment it went out.
 */
internal data class PublishedSnapshot(
    val snapshot: NowPlayingSnapshot,
    val atMs: Long,
)

/**
 * How far the real position may drift from the watch's own extrapolation before it is worth a
 * Bluetooth write.
 *
 * Three seconds is under what anyone notices on a progress ring, and comfortably above the jitter
 * between a position sampled every half second and a clock ticking on another device. Anything
 * larger than this is a seek, a skip or an episode change — all of which the watch must be told
 * about at once.
 */
private const val POSITION_DRIFT_TOLERANCE_MS = 3_000L

/**
 * Decides whether [candidate] is worth publishing.
 *
 * The player emits several states a second while playing, and each publish is a Bluetooth write, so
 * most of them must be dropped. Two things justify one: something substantive changed (what is
 * loaded, whether it is playing, the speed, the queue), or the position is no longer where the
 * watch would have extrapolated it to — which is precisely the signature of a seek.
 *
 * @param previous the last snapshot actually sent, or null if none has been.
 * @param candidate the snapshot just produced.
 * @param nowMs the phone's wall clock.
 */
internal fun shouldPublish(
    previous: PublishedSnapshot?,
    candidate: NowPlayingSnapshot,
    nowMs: Long,
): Boolean {
    if (previous == null) return true
    if (previous.snapshot.withoutTiming() != candidate.withoutTiming()) return true

    val extrapolated = previous.snapshot.positionAfter(nowMs - previous.atMs)
    return abs(extrapolated - candidate.positionMs) > POSITION_DRIFT_TOLERANCE_MS
}

/**
 * Publishes the phone's playback state to the watch.
 *
 * The watch renders a remote control, so it needs to know what the phone is playing; this is the
 * only thing that tells it. State goes out as a Data Layer *data item* rather than a message,
 * because the Data Layer keeps the last item and hands it to the watch on connect — so the watch
 * app opens showing the right episode even if the phone has said nothing for an hour.
 *
 * Publishing is deliberately sparse; see [shouldPublish] for what earns a write, and
 * [NowPlayingSnapshot.positionAfter] for how the watch fills in the gaps.
 *
 * @property connection the phone's player.
 * @property playbackRepository the durable queue and the user's playback preferences.
 * @property dataClient the Data Layer.
 * @property artworkAssets downscales cover art for the watch, which cannot fetch it itself.
 * @property clock the phone's wall clock, injected so the publish decision can be tested.
 * @property scope application scope: playback outlives every screen, and so must this.
 */
@Singleton
internal class NowPlayingPublisher @Inject constructor(
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    private val dataClient: DataClient,
    private val artworkAssets: ArtworkAssets,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Guards [start] so that a second caller does not open a second collection. */
    private val started = AtomicBoolean(false)

    /**
     * The last snapshot sent.
     *
     * Only touched from the single collector coroutine and from [publishCurrent], both of which run
     * on the application scope's dispatcher, so no further synchronisation is needed. It is marked
     * `@Volatile` regardless because that dispatcher is a thread pool and the two may land on
     * different threads.
     */
    @Volatile
    private var lastPublished: PublishedSnapshot? = null

    /**
     * Starts mirroring playback state to the watch. Idempotent.
     *
     * @return the collection job, of interest only to tests.
     */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            combine(
                connection.playbackState,
                playbackRepository.observePlaybackSettings(),
                playbackRepository.observeQueue(),
            ) { playback, settings, queue ->
                nowPlayingSnapshot(playback, settings, queue, clock.millis())
            }.collect { snapshot ->
                if (shouldPublish(lastPublished, snapshot, clock.millis())) publish(snapshot)
            }
        }
    }

    /**
     * Publishes the current state immediately, whether or not anything changed.
     *
     * This is what [WearCommand.RequestState][md.borisveriga.bpodcat.core.wearprotocol.WearCommand.RequestState]
     * triggers when the watch app opens: the cached data item it already has may predate the phone
     * being restarted, and only a fresh read can confirm it.
     */
    suspend fun publishCurrent() {
        val snapshot = nowPlayingSnapshot(
            playback = connection.currentState(),
            settings = playbackRepository.observePlaybackSettings().first(),
            queue = playbackRepository.observeQueue().first(),
            publishedAtMs = clock.millis(),
        )
        publish(snapshot)
    }

    /**
     * Writes one snapshot to the Data Layer.
     *
     * Failures are swallowed: there is no watch paired, or Play Services is unavailable, and
     * neither is a reason to disturb playback on the phone.
     */
    private suspend fun publish(snapshot: NowPlayingSnapshot) {
        // Resolved before the request is built, and allowed to fail: artwork is decoration, and an
        // image that will not load must never stop the watch being told what is playing.
        val artwork = artworkAssets.assetFor(snapshot.artworkUrl)

        val request = PutDataMapRequest.create(WearPaths.NOW_PLAYING).apply {
            dataMap.putByteArray(WearPaths.PAYLOAD_KEY, WearMessages.encodeSnapshot(snapshot))
            artwork?.let { dataMap.putAsset(WearPaths.ARTWORK_KEY, it) }
        }.asPutDataRequest().setUrgent()

        val sent = suspendRunCatching { dataClient.putDataItem(request).await() }.isSuccess
        // Only a delivered snapshot may become the baseline: recording a failed one would suppress
        // the next publish as a duplicate and leave the watch showing stale state indefinitely.
        if (sent) lastPublished = PublishedSnapshot(snapshot, clock.millis())
    }
}

package md.borisveriga.bpodcat.wearsync

import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import md.borisveriga.bpodcat.core.common.di.ApplicationScope
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.EpisodeWithShow
import md.borisveriga.bpodcat.core.wearprotocol.OfflineEpisode
import md.borisveriga.bpodcat.core.wearprotocol.OfflineLibrary
import md.borisveriga.bpodcat.core.wearprotocol.WearMessages
import md.borisveriga.bpodcat.core.wearprotocol.WearPaths

/**
 * The most episodes the watch is offered.
 *
 * A data item is capped at 100 KB and this one is a list of titles; forty of them is a few kilobytes
 * and already more than anyone will scroll on a wrist. The cap exists so that a library of two
 * thousand downloaded episodes cannot silently overflow the item and take the whole list with it.
 */
private const val MAX_OFFERED_EPISODES = 40

/**
 * Turns the phone's downloads into the list the watch chooses from.
 *
 * Only *completed* downloads are offered. Anything else would be an entry the watch could tap and
 * get half an episode from, and there is nothing useful the watch could do about that.
 *
 * @param downloads what the phone holds, in the order the downloads screen shows it.
 * @return the library to publish.
 */
internal fun offlineLibraryOf(downloads: List<EpisodeWithShow>): OfflineLibrary = OfflineLibrary(
    episodes = downloads
        .filter { it.episode.downloadState == DownloadState.COMPLETED }
        .take(MAX_OFFERED_EPISODES)
        .map { entry ->
            OfflineEpisode(
                id = entry.episode.id,
                title = entry.episode.title,
                showTitle = entry.showTitle,
                durationMs = entry.episode.durationMs ?: 0L,
                // What Media3 actually wrote, not what the feed claimed: this number is shown to
                // the user before a transfer that will take minutes, and feeds are routinely wrong
                // about enclosure lengths.
                sizeBytes = entry.episode.downloadedBytes,
            )
        },
)

/**
 * The libraries worth publishing, out of everything the downloads screen shows.
 *
 * What comes in changes every time a download's byte count moves — several times a second while one
 * is running — and almost none of that reaches this list, which only knows about downloads that have
 * *finished*. Deriving first and comparing second is what keeps [offlineLibraryRequest]'s urgency
 * honest: one write when a download completes, and none in between.
 *
 * @param downloads the phone's downloads, as they change.
 * @return the list to offer the watch, each time it is actually different from the last.
 */
internal fun offlineLibraries(downloads: Flow<List<EpisodeWithShow>>): Flow<OfflineLibrary> =
    downloads.map(::offlineLibraryOf).distinctUntilChanged()

/**
 * Builds the data item one library travels in.
 *
 * **Urgent, like the now-playing snapshot.** A data item that does not say so is batched by the Data
 * Layer and may not cross for up to half an hour, which for this list is indistinguishable from
 * broken: the moment it is wanted is the moment a download has just finished and the phone is about
 * to be left on a table. Urgency is affordable here only because the caller publishes when the list
 * *changes* rather than on every download event; see [OfflineLibraryPublisher.start].
 *
 * The payload deliberately carries no timestamp, unlike the snapshot's. The Data Layer drops an item
 * whose bytes are unchanged, and a list that has not changed is a list the watch already has.
 *
 * @param library what to offer the watch.
 * @return the request to put.
 */
internal fun offlineLibraryRequest(library: OfflineLibrary): PutDataRequest =
    PutDataMapRequest.create(WearPaths.OFFLINE_LIBRARY).apply {
        dataMap.putByteArray(WearPaths.PAYLOAD_KEY, WearMessages.encodeLibrary(library))
    }.asPutDataRequest().setUrgent()

/**
 * Publishes the phone's offline library to the watch.
 *
 * Kept apart from [NowPlayingPublisher] because the two change on completely different clocks —
 * playback state several times a minute, this a few times a week — and merging them would mean
 * rewriting the whole download list every time somebody pressed pause.
 *
 * @property downloadRepository the phone's downloads, as they change.
 * @property dataClient the Data Layer.
 * @property scope application scope: downloads finish while no screen is open, and the watch's copy
 *   of the list has to keep up with them.
 */
@Singleton
internal class OfflineLibraryPublisher @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val dataClient: DataClient,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Guards [start] so a second caller does not open a second collection. */
    private val started = AtomicBoolean(false)

    /**
     * Starts mirroring the download list to the watch. Idempotent.
     *
     * Only genuine changes are published; see [offlineLibraries] for why that matters now that the
     * write is urgent.
     *
     * @return the collection job, of interest only to tests.
     */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            offlineLibraries(downloadRepository.observeDownloads()).collect { publish(it) }
        }
    }

    /**
     * Publishes the list as it stands, whether or not it changed.
     *
     * Answers the watch app opening: its cached copy may predate a download that has since finished,
     * or the phone being reinstalled.
     */
    suspend fun publishCurrent() {
        publish(offlineLibraryOf(downloadRepository.observeDownloads().first()))
    }

    /**
     * Writes one library to the Data Layer.
     *
     * Failures are swallowed for the same reason as the snapshot's: there is no watch paired, or
     * Play Services is unavailable, and neither is a reason to disturb the phone.
     */
    private suspend fun publish(library: OfflineLibrary) {
        suspendRunCatching { dataClient.putDataItem(offlineLibraryRequest(library)).await() }
    }
}

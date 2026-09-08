package md.borisveriga.megapodcastplayer.wear.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand

/**
 * A mark made on the watch that the phone has not been told about.
 *
 * @property episodeId the episode being played on the watch.
 * @property positionMs where in it the wearer marked.
 */
@Serializable
data class PendingMoment(
    val episodeId: String,
    val positionMs: Long,
)

/** The queue file's contents. */
@Serializable
private data class PendingIndex(val moments: List<PendingMoment> = emptyList())

/**
 * Carries moments marked on the watch back to the phone, eventually.
 *
 * The same argument as [PositionReporter], one step further. A position played out of range is
 * *recoverable* — the watch keeps it in its own index and reports it later — but a moment is not
 * recoverable from anything: it is a claim about one second of one episode, made once, and if the
 * message that carried it was dropped there is nothing left to reconstruct it from. Marking
 * something worth remembering halfway round a run and finding nothing there afterwards is the
 * failure this class exists to prevent.
 *
 * Only marks made while the watch is playing its own copy are queued. A mark sent as a remote
 * control names nothing — it means "wherever the phone is right now" — and a phone that could not
 * be reached is a phone that is not playing, so there is no position to save and nothing to send
 * later; that case is reported as a failed command instead.
 *
 * Files rather than a database, for the reason [WatchEpisodeStore] gives: this is a handful of
 * two-field records, rewritten whole.
 *
 * @property context used only for its private files directory.
 * @property client the route to the phone.
 * @property ioDispatcher every method here touches the disk.
 */
@Singleton
class PendingMoments @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: PhonePlayerClient,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /** Serialises the read-modify-write of the queue file, which several callers reach. */
    private val mutex = Mutex()

    private val json = Json

    private val queueFile: File get() = File(context.filesDir, QUEUE_FILE)

    /**
     * Marks a moment in an episode the watch is playing.
     *
     * Sent first and queued only if it did not arrive, rather than queued and swept later: the
     * phone is usually in a pocket a metre away, and a moment that lands immediately is one the
     * phone's own screen can show before the wearer has put their arm down.
     *
     * @param episodeId the episode playing on the watch.
     * @param positionMs where in it to mark.
     * @return true when the phone took it now; false when it was written down for later. Both are
     *   successes as far as the wearer is concerned — the distinction is only whether the phone
     *   already knows.
     */
    suspend fun mark(episodeId: String, positionMs: Long): Boolean {
        val moment = PendingMoment(episodeId = episodeId, positionMs = positionMs)
        val delivered = client.send(
            WearCommand.MarkMoment(episodeId = moment.episodeId, positionMs = moment.positionMs),
        )
        if (!delivered) enqueue(moment)
        return delivered
    }

    /**
     * Sends everything the phone has not been told, oldest first.
     *
     * Called when the phone comes back into range. One at a time: these are tiny messages over the
     * link that is the scarce resource, and there will be a handful at most.
     *
     * A moment that fails again stays queued, so a phone that goes out of range mid-flush loses
     * nothing.
     *
     * @return how many were delivered.
     */
    suspend fun flush(): Int {
        val queued = read()
        if (queued.isEmpty()) return 0

        val delivered = mutableListOf<PendingMoment>()
        for (moment in queued) {
            val sent = client.send(
                WearCommand.MarkMoment(
                    episodeId = moment.episodeId,
                    positionMs = moment.positionMs,
                ),
            )
            if (!sent) break
            delivered += moment
        }

        if (delivered.isNotEmpty()) {
            mutex.withLock { write(read().filterNot { it in delivered }) }
        }
        return delivered.size
    }

    /**
     * Adds one moment to the queue.
     *
     * Duplicates are dropped here as well as on the phone. The phone folds two marks a few seconds
     * apart into one, but only once it has seen them; two identical entries in this file would
     * otherwise survive a week of being out of range and arrive as two messages.
     *
     * @param moment the mark to keep.
     */
    private suspend fun enqueue(moment: PendingMoment) = mutex.withLock {
        val existing = read()
        if (moment in existing) return@withLock
        write(existing + moment)
    }

    /**
     * Reads the queue.
     *
     * A missing or unreadable file reads as "nothing pending" rather than as an error: there is
     * nothing useful to do about a corrupt queue, and refusing to mark anything else because of it
     * would be worse than losing what it held.
     *
     * @return the queued marks, oldest first.
     */
    private suspend fun read(): List<PendingMoment> = withContext(ioDispatcher) {
        suspendRunCatching {
            queueFile.takeIf { it.exists() }
                ?.readText()
                ?.let { json.decodeFromString(PendingIndex.serializer(), it) }
                ?.moments
                .orEmpty()
        }.getOrElse { failure ->
            Log.w(TAG, "Could not read the watch's pending moments", failure)
            emptyList()
        }
    }

    /**
     * Rewrites the queue whole.
     *
     * @param moments what should be in it.
     */
    private suspend fun write(moments: List<PendingMoment>) = withContext(ioDispatcher) {
        suspendRunCatching {
            queueFile.writeText(json.encodeToString(PendingIndex.serializer(), PendingIndex(moments)))
        }.onFailure { failure ->
            Log.w(TAG, "Could not write the watch's pending moments", failure)
        }
        Unit
    }

    private companion object {
        const val QUEUE_FILE = "pending-moments.json"
        const val TAG = "PendingMoments"
    }
}

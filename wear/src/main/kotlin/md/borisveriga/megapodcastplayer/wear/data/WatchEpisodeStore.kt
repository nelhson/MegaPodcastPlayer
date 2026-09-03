package md.borisveriga.megapodcastplayer.wear.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineEpisode

/**
 * An episode whose audio is on the watch.
 *
 * This is the watch's own record, not the phone's: the phone knows what it sent, but only the watch
 * knows what arrived, how far through it the wearer got, and whether the phone has been told.
 *
 * @property id the episode, the same id the phone uses.
 * @property title episode title, copied from the offer when the transfer started.
 * @property showTitle the owning podcast, which also colours its rows.
 * @property durationMs how long it runs, or `0` when the phone never knew.
 * @property sizeBytes what the file takes on the watch.
 * @property positionMs how far the wearer has got, played on the watch.
 * @property isPlayed whether it reached the end here.
 * @property positionReported whether the phone has been told [positionMs]. False after every local
 *   play, true once a report gets through; see [PositionReporter] for what does the telling.
 * @property addedAtMs the watch's wall clock when the transfer finished, which is the order the list
 *   is shown in.
 */
@Serializable
data class StoredEpisode(
    val id: String,
    val title: String,
    val showTitle: String = "",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val positionMs: Long = 0L,
    val isPlayed: Boolean = false,
    val positionReported: Boolean = true,
    val addedAtMs: Long = 0L,
) {

    /** Fraction played, in `0f..1f`; `0f` while the duration is unknown. */
    val progress: Float
        get() = durationMs.takeIf { it > 0L }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) }
            ?: 0f
}

/** The index file's contents; a wrapper so the format can gain fields without becoming a list. */
@Serializable
private data class StoredIndex(val episodes: List<StoredEpisode> = emptyList())

/**
 * The episodes held on the watch, and their audio.
 *
 * This is the difference between a remote control and a player: everything else on this watch is a
 * view of the phone, and this is the one thing the watch owns. It survives the phone being out of
 * range, out of battery or at home, which is the entire point of it.
 *
 * Files, not a database. The watch stores a handful of episodes described by six fields each; a Room
 * dependency, a schema and a migration path would be more machinery than the thing being stored. The
 * index is rewritten whole on every change — at this size that is one small write, and it cannot
 * leave two files disagreeing about what exists.
 *
 * Audio arrives under a `.part` name and is renamed only once the byte count checks out, so a
 * transfer cut off by a closed lid leaves nothing that looks playable.
 *
 * @property context used only for its private files directory.
 * @property ioDispatcher every method here touches the disk.
 */
@Singleton
class WatchEpisodeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /** Serialises writes to the index and to [episodes], which arrive from several callers. */
    private val mutex = Mutex()

    private val json = Json {
        // A watch downgraded to an older build must still read an index a newer one wrote.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val directory: File get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private val indexFile: File get() = File(directory, INDEX_FILE)

    private val _episodes = MutableStateFlow(emptyList<StoredEpisode>())

    /**
     * What is on the watch, newest first.
     *
     * Starts empty and fills in on the first [load]; a screen collecting this therefore renders an
     * empty list for one frame rather than blocking on a disk read.
     */
    val episodes: StateFlow<List<StoredEpisode>> = _episodes.asStateFlow()

    private val _transfers = MutableStateFlow(emptyMap<String, TransferProgress>())

    /** Transfers currently arriving, by episode id. */
    val transfers: StateFlow<Map<String, TransferProgress>> = _transfers.asStateFlow()

    /**
     * Reads the index from disk into [episodes].
     *
     * Called on start-up and after anything outside this class could have changed the directory. A
     * missing or unreadable index reads as "nothing stored" rather than as an error: the audio files
     * beside it are worthless without their titles, and a watch that refused to start because of a
     * truncated JSON file would be worse than one that had forgotten a download.
     */
    suspend fun load() {
        val loaded = withContext(ioDispatcher) {
            suspendRunCatching {
                indexFile.takeIf { it.exists() }
                    ?.readText()
                    ?.let { json.decodeFromString(StoredIndex.serializer(), it) }
                    ?.episodes
                    .orEmpty()
            }.getOrElse { error ->
                Log.w(TAG, "Could not read the watch's episode index", error)
                emptyList()
            }
        }
        // Only entries whose audio is actually there: a file deleted underneath us must not stay in
        // a list whose rows all claim to be playable.
        mutex.withLock { _episodes.value = loaded.filter { audioFile(it.id).exists() } }
    }

    /**
     * Takes one episode's audio off a channel and onto the disk.
     *
     * Runs on the caller's coroutine — which is a Data Layer callback, kept alive precisely so this
     * can finish — and reports progress into [transfers] as it goes.
     *
     * @param offer what the phone said it was sending, from the published library. Null when the
     *   watch has no record of the offer, in which case the episode is stored under its id alone and
     *   nothing can be checked against.
     * @param input the channel's stream; closed by the caller, which owns it.
     * @return true when a complete episode landed.
     */
    suspend fun receive(offer: OfflineEpisode, input: InputStream): Boolean {
        val partial = File(directory, "${offer.id}$PARTIAL_SUFFIX")
        _transfers.update { it + (offer.id to TransferProgress(0L, offer.sizeBytes)) }

        val written = withContext(ioDispatcher) {
            suspendRunCatching {
                partial.outputStream().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        // A transfer the wearer walked away from must stop taking the radio with it.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        total += read
                        _transfers.update {
                            it + (offer.id to TransferProgress(total, offer.sizeBytes))
                        }
                    }
                    total
                }
            }.getOrElse { error ->
                Log.w(TAG, "Receiving ${offer.id} failed part way", error)
                null
            }
        }

        _transfers.update { it - offer.id }

        val complete = written != null && isComplete(written, offer.sizeBytes)
        if (!complete) {
            // A short file is the ordinary shape of a dropped Bluetooth link, and it would otherwise
            // sit in the list looking playable and stop halfway through.
            withContext(ioDispatcher) { partial.delete() }
            return false
        }

        return finish(offer, partial, written)
    }

    /**
     * Renames a finished transfer into place and records it.
     *
     * @param offer what was received.
     * @param partial the file it was written to.
     * @param sizeBytes what actually arrived, which is what the row will show.
     */
    private suspend fun finish(
        offer: OfflineEpisode,
        partial: File,
        sizeBytes: Long,
    ): Boolean = mutex.withLock {
        val renamed = withContext(ioDispatcher) {
            val target = audioFile(offer.id)
            target.delete()
            partial.renameTo(target)
        }
        if (!renamed) {
            withContext(ioDispatcher) { partial.delete() }
            return@withLock false
        }

        val stored = StoredEpisode(
            id = offer.id,
            title = offer.title,
            showTitle = offer.showTitle,
            durationMs = offer.durationMs,
            sizeBytes = sizeBytes,
            addedAtMs = System.currentTimeMillis(),
        )
        writeIndex(_episodes.value.filterNot { it.id == offer.id } + stored)
        true
    }

    /**
     * Records how far through an episode the wearer got.
     *
     * Marks the position unreported, which is what later gets it back to the phone; see
     * [PositionReporter].
     *
     * @param episodeId the episode played.
     * @param positionMs where it was left.
     * @param isPlayed whether it reached the end.
     */
    suspend fun setPosition(episodeId: String, positionMs: Long, isPlayed: Boolean) =
        mutex.withLock {
            val updated = _episodes.value.map { episode ->
                if (episode.id != episodeId) {
                    episode
                } else {
                    episode.copy(
                        positionMs = positionMs,
                        isPlayed = isPlayed,
                        positionReported = false,
                    )
                }
            }
            writeIndex(updated)
        }

    /**
     * Notes that the phone has been told about an episode's position.
     *
     * @param episodeId the episode whose position was delivered.
     */
    suspend fun markPositionReported(episodeId: String) = mutex.withLock {
        writeIndex(
            _episodes.value.map {
                if (it.id == episodeId) it.copy(positionReported = true) else it
            },
        )
    }

    /**
     * Deletes one episode's audio and forgets it.
     *
     * @param episodeId the episode to remove.
     */
    suspend fun remove(episodeId: String) = mutex.withLock {
        withContext(ioDispatcher) { audioFile(episodeId).delete() }
        writeIndex(_episodes.value.filterNot { it.id == episodeId })
    }

    /** Deletes everything the watch holds — the "free up space" button. */
    suspend fun removeAll() = mutex.withLock {
        withContext(ioDispatcher) {
            _episodes.value.forEach { audioFile(it.id).delete() }
        }
        writeIndex(emptyList())
    }

    /**
     * Where an episode's audio lives.
     *
     * The file has no extension naming its container, because the watch does not know one: what
     * arrived is whatever the publisher served the phone. The player sniffs it, which it has to do
     * for a podcast feed's contents in any case.
     *
     * @param episodeId the episode.
     */
    fun audioFile(episodeId: String): File = File(directory, "$episodeId$AUDIO_SUFFIX")

    /** Total bytes the stored episodes occupy. */
    val usedBytes: Long get() = _episodes.value.sumOf { it.sizeBytes }

    /**
     * Writes the index and publishes it.
     *
     * Always called under [mutex]: the in-memory list and the file must not be able to disagree,
     * and two transfers finishing at once would otherwise race to write two different lists.
     */
    private suspend fun writeIndex(episodes: List<StoredEpisode>) {
        val sorted = episodes.sortedByDescending { it.addedAtMs }
        withContext(ioDispatcher) {
            suspendRunCatching {
                indexFile.writeText(json.encodeToString(StoredIndex.serializer(), StoredIndex(sorted)))
            }.onFailure { Log.w(TAG, "Could not write the watch's episode index", it) }
        }
        _episodes.value = sorted
    }

    /**
     * Whether what arrived is the whole episode.
     *
     * @param written how many bytes landed.
     * @param expected what the phone offered, or `0` when it could not say — in which case any
     *   non-empty file is accepted, since there is nothing to compare against and refusing would
     *   mean refusing every episode whose length the phone never learned.
     */
    private fun isComplete(written: Long, expected: Long): Boolean =
        if (expected > 0L) written >= expected else written > 0L

    private companion object {
        const val TAG = "WatchEpisodeStore"

        /** Under the app's private files, so uninstalling the watch app takes the audio with it. */
        const val DIRECTORY = "watch_episodes"

        const val INDEX_FILE = "index.json"
        const val AUDIO_SUFFIX = ".audio"

        /** A transfer in progress; never playable, never listed. */
        const val PARTIAL_SUFFIX = ".part"

        /** Matches the phone's send buffer; the link between them is the slow part either way. */
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * How much of a transfer has arrived.
 *
 * @property receivedBytes what has landed so far.
 * @property expectedBytes what the phone offered, or `0` when it could not say.
 */
data class TransferProgress(
    val receivedBytes: Long,
    val expectedBytes: Long,
) {

    /**
     * Fraction complete, in `0f..1f`.
     *
     * Returns `0f` when the phone did not say how large the episode is: a bar that cannot move is
     * better than one that jumps to the end and waits there.
     */
    val fraction: Float
        get() = expectedBytes.takeIf { it > 0L }
            ?.let { (receivedBytes.toFloat() / it).coerceIn(0f, 1f) }
            ?: 0f
}

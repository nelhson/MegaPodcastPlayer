package md.borisveriga.megapodcastplayer.core.data.export

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.asExternalModel
import md.borisveriga.megapodcastplayer.core.media.download.DownloadedAudioReader
import md.borisveriga.megapodcastplayer.core.model.AudioContainer
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import md.borisveriga.megapodcastplayer.core.model.downloadListMarkdown
import md.borisveriga.megapodcastplayer.core.model.exportFileName
import md.borisveriga.megapodcastplayer.core.model.exportFileNameWithoutPosition
import md.borisveriga.megapodcastplayer.core.model.exportFolderName
import md.borisveriga.megapodcastplayer.core.model.exportListFileName

/**
 * Downloads the episodes of one show that are on screen, then copies them out of the download cache
 * into a folder the user named and placed.
 *
 * What the user gets is that folder holding one ordinary audio file per episode, numbered in the
 * show's own order, and a Markdown list of what they are: `Talks/001 - First talk.m4a` beside
 * `Talks/Talks.md`. That is a playlist any other player, file manager or car stereo can read, which
 * a download inside Media3's cache is not.
 *
 * ## Downloading first
 *
 * The episodes are the ones the show page's filter lists, worked out once when the run starts. Any
 * of them not yet on the device is asked for through [DownloadRepository], which is the same request
 * the row's download button makes, so the "Wi-Fi only" rule applies and Media3's own service does
 * the transfer. The run then waits until none of them is queued or transferring. An episode whose
 * download failed, or that was deleted meanwhile, is counted as [ExportSummary.failed] and the rest
 * are copied.
 *
 * ## One episode's failure is not the export's
 *
 * Each episode is copied on its own. A span missing from the cache or a provider that refuses one
 * name costs that episode, is recorded, and the export carries on; only not being able to reach the
 * folder at all fails the whole run.
 *
 * ## Running it again
 *
 * An episode already in the folder is left alone and counted as [ExportSummary.alreadyThere].
 * "Already there" means a file with the same name *after its number* and the same size as the
 * download. The number is left out because positions move between runs: a new video sorts to `001`
 * and a deleted episode moves everything after it up by one. The size is checked because a copy cut
 * short by the process dying keeps its name; a file that fails the check is replaced.
 *
 * Exporting into the same folder twice therefore picks up where an interrupted run stopped, and adds
 * only what is new. A copy that fails or is cancelled deletes its partial file straight away, and
 * the size check catches the ones that could not be deleted. The list is rewritten every time.
 *
 * @property podcastDao confirms the show still exists.
 * @property episodeDao lists the show's episodes in export order, and watches their downloads.
 * @property downloadRepository asks for the episodes that are not on the device yet.
 * @property reader reads each episode's audio back out of the cache.
 * @property directory the picked folder.
 * @property crashReporter told about each episode that could not be written, which the user only
 *   sees as a count, and about a list that could not be written, which the user does not see at all.
 * @property clock dates the list.
 * @property ioDispatcher everything here blocks on disk.
 */
@Singleton
class EpisodeAudioExporter @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val downloadRepository: DownloadRepository,
    private val reader: DownloadedAudioReader,
    private val directory: ExportDirectory,
    private val crashReporter: CrashReporter,
    private val clock: Clock,
    @param:Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Downloads, then exports, the episodes of one show that a filter lists.
     *
     * @param podcastId the show.
     * @param treeUri the location the user picked.
     * @param folderName the name the user gave the folder inside it; cleaned of what a file system
     *   refuses.
     * @param filter the show page's filter, which decides the episodes.
     * @param onProgress told as downloads finish, then after each episode is copied, and once before
     *   each stage starts.
     * @return what happened to each episode, or the failure that stopped the export reaching the
     *   folder at all. A show that no longer exists is a failure too.
     */
    suspend fun export(
        podcastId: String,
        treeUri: String,
        folderName: String,
        filter: EpisodeFilter,
        onProgress: suspend (ExportProgress) -> Unit,
    ): Result<ExportSummary> = withContext(ioDispatcher) {
        suspendRunCatching {
            checkNotNull(podcastDao.getById(podcastId)) { "No show $podcastId" }
            val selected = episodeDao.getForExport(podcastId)
                .filter { filter.matches(it.asExternalModel()) }

            // Reached before anything is downloaded: a folder the app cannot write to should fail
            // the run now, not after an hour of downloading.
            val folder = directory.findOrCreateFolder(
                parent = directory.root(treeUri),
                name = exportFolderName(folderName),
            )

            awaitDownloads(podcastId, selected.map { it.id }, onProgress)
            val copied = copyAll(podcastId, folder, selected, onProgress)
            writeList(folder, folderName, copied.inFolderIds)
            copied.summary
        }.onFailure { failure ->
            crashReporter.recordNonFatal("Episode audio export could not start", failure)
        }
    }

    /**
     * Asks for every selected episode that is not on the device, and waits until none is pending.
     *
     * Waiting on the rows rather than on Media3 keeps this on the one source of truth the rest of
     * the app reads. [DownloadRepository.download] marks a row queued before it returns, so the wait
     * does not start from the old state.
     *
     * Two things can still make a row lie, and each is answered here rather than trusted:
     *
     * - A row can be *queued* in Room while Media3 has never heard of it: the queued state is
     *   written before the download service is started, and a start the platform refuses (a
     *   background auto-download, say) is swallowed. Waiting on such a row would never end, so
     *   queued rows are asked for again too. Media3 treats a second request for a download it
     *   already has as the same download.
     * - In a fresh process the start-up reconcile writes Media3's own statuses, and can land after
     *   this run's queued write — turning a requested episode back into "not downloaded" before its
     *   download has been seen. So when the wait ends with episodes still missing, they are asked
     *   for once more and waited on again. A download that genuinely fails is therefore retried
     *   once, which is also what the user would do.
     *
     * @param podcastId the show, whose rows are re-read before each round.
     * @param ids the export's episodes.
     * @param onProgress told how many are on the device, each time that changes.
     */
    private suspend fun awaitDownloads(
        podcastId: String,
        ids: List<String>,
        onProgress: suspend (ExportProgress) -> Unit,
    ) {
        val total = ids.size
        onProgress(ExportProgress(done = 0, total = total, stage = ExportStage.DOWNLOADING))
        if (ids.isEmpty()) return

        for (round in 1..DOWNLOAD_ROUNDS) {
            val wanted = ids.toSet()
            val missing = episodeDao.getForExport(podcastId)
                .filter { it.id in wanted && it.downloadState in REQUESTABLE_STATES }
            if (missing.isEmpty() && round > 1) return
            missing.forEach { downloadRepository.download(it.id) }
            awaitSettled(ids, total, onProgress)
        }
    }

    /**
     * Waits until none of the given episodes is queued or transferring.
     *
     * @param ids the export's episodes.
     * @param total the export's size, for progress.
     * @param onProgress told how many are on the device, each time that changes.
     */
    private suspend fun awaitSettled(
        ids: List<String>,
        total: Int,
        onProgress: suspend (ExportProgress) -> Unit,
    ) {
        episodeDao.observeDownloadStates(ids)
            // Rows are rewritten on every percent of every download; only a change of state is news.
            .distinctUntilChanged()
            .onEach { states ->
                val done = states.count { it == DownloadState.COMPLETED }
                onProgress(ExportProgress(done = done, total = total, stage = ExportStage.DOWNLOADING))
            }
            .first { states -> states.none { it in PENDING_STATES } }
    }

    /**
     * Copies every selected episode that is now downloaded.
     *
     * Positions are counted over the whole selection, so an episode that failed to download leaves a
     * gap in the numbers rather than renumbering everything after it — the next run, once it has
     * downloaded, fills the gap without moving any other file.
     *
     * @param podcastId the show, re-read so the copy sees the downloads that just finished.
     * @param folder the export's folder.
     * @param selected the export's episodes, in export order.
     * @param onProgress told after each episode.
     * @return what became of each episode, and which of them are now in the folder.
     */
    private suspend fun copyAll(
        podcastId: String,
        folder: String,
        selected: List<EpisodeEntity>,
        onProgress: suspend (ExportProgress) -> Unit,
    ): CopyResult {
        val total = selected.size
        onProgress(ExportProgress(done = 0, total = total, stage = ExportStage.COPYING))
        val downloaded = episodeDao.getForExport(podcastId)
            .filter { it.downloadState == DownloadState.COMPLETED }
            .associateBy { it.id }
        val existing = directory.files(folder).toMutableList()

        var summary = ExportSummary(exported = 0, alreadyThere = 0, failed = 0)
        val inFolder = mutableListOf<String>()
        selected.forEachIndexed { index, episode ->
            val current = downloaded[episode.id]
            val outcome = if (current == null) {
                Outcome.FAILED
            } else {
                exportOne(folder, existing, current, index + 1, total)
            }
            if (outcome != Outcome.FAILED) inFolder += episode.id
            summary = when (outcome) {
                Outcome.EXPORTED -> summary.copy(exported = summary.exported + 1)
                Outcome.ALREADY_THERE -> summary.copy(alreadyThere = summary.alreadyThere + 1)
                Outcome.FAILED -> summary.copy(failed = summary.failed + 1)
            }
            onProgress(ExportProgress(done = index + 1, total = total, stage = ExportStage.COPYING))
        }
        return CopyResult(summary, inFolder)
    }

    /**
     * Writes the Markdown list of the exported episodes into the folder, replacing an earlier one.
     *
     * Best effort: the audio is what the user asked for and is already in the folder, so a list that
     * cannot be written is recorded and does not fail the run.
     *
     * @param folder the export's folder.
     * @param folderName the name the user gave it, which the list is named after.
     * @param ids the episodes whose audio is now in the folder, so the list never names a file the
     *   folder does not have.
     */
    private suspend fun writeList(folder: String, folderName: String, ids: List<String>) {
        if (ids.isEmpty()) return
        suspendRunCatching {
            val rows = episodeDao.getDownloadListForIds(ids)
            if (rows.isNotEmpty()) {
                val markdown = downloadListMarkdown(rows.map { it.asExternalModel() }, clock.millis())
                val name = exportListFileName(folderName)
                directory.files(folder)
                    .filter { it.name == name }
                    .forEach { directory.delete(it.location) }
                val file = directory.createFile(folder, name, LIST_MIME_TYPE)
                writeOrDelete(file) { output -> output.write(markdown.toByteArray(Charsets.UTF_8)) }
            }
        }.onFailure { failure ->
            crashReporter.recordNonFatal("Download list could not be written to the export", failure)
        }
    }

    /**
     * Writes one episode, unless its file is already there.
     *
     * @param folder the show's folder.
     * @param existing files in the folder not yet matched to an episode. A match is removed, so two
     *   episodes whose titles clean to the same name cannot both claim one file.
     * @param episode the episode.
     * @param position its place in the export, from 1.
     * @param total the export's size.
     * @return what became of it.
     */
    private suspend fun exportOne(
        folder: String,
        existing: MutableList<ExportedFile>,
        episode: EpisodeEntity,
        position: Int,
        total: Int,
    ): Outcome {
        if (!reader.isFullyDownloaded(episode.audioUrl)) {
            crashReporter.recordNonFatal(
                "Episode audio export found an incomplete download",
                IllegalStateException("Not fully cached: ${episode.id}"),
            )
            return Outcome.FAILED
        }

        return suspendRunCatching {
            reader.open(episode.audioUrl).buffered(COPY_BUFFER_BYTES).use { input ->
                val container = AudioContainer.sniff(input.peek(AudioContainer.HEADER_BYTES))
                val name = exportFileName(position, total, episode.title, container.extension)
                val length = checkNotNull(reader.contentLength(episode.audioUrl)) {
                    "No recorded length: ${episode.id}"
                }

                val copy = existing.firstOrNull { it.isWholeCopy(name, length) }
                if (copy != null) {
                    existing -= copy
                    return@use Outcome.ALREADY_THERE
                }
                // A file under this very name that is not a whole copy is what an interrupted run
                // left behind. Replaced rather than kept beside, so the folder has one of each.
                existing.firstOrNull { it.name == name }?.let { stale ->
                    directory.delete(stale.location)
                    existing -= stale
                }

                val file = directory.createFile(folder, name, container.mimeType)
                writeOrDelete(file) { output -> input.copyCancellably(output) }
                Outcome.EXPORTED
            }
        }.getOrElse { failure ->
            crashReporter.recordNonFatal("Episode audio export failed", failure)
            Outcome.FAILED
        }
    }

    /**
     * Whether this file is a finished copy of the episode that would be written as [name].
     *
     * Same name apart from the number, and as many bytes as the download. A storage that reports no
     * size gets the benefit of the doubt, as every file did before sizes were compared.
     *
     * @param name the name the episode would be written under now.
     * @param length the download's length in bytes.
     */
    private fun ExportedFile.isWholeCopy(name: String, length: Long): Boolean {
        val title = exportFileNameWithoutPosition(name) ?: return this.name == name
        return exportFileNameWithoutPosition(this.name) == title &&
            (sizeBytes == null || sizeBytes == length)
    }

    /**
     * Runs [write] against a new file, and deletes the file unless the write finished.
     *
     * A `finally` rather than a catch, so the one path is the same for a failure and a cancellation
     * and neither can be swallowed here.
     *
     * @param file the file just created.
     * @param write copies into the file's stream.
     */
    private suspend fun writeOrDelete(file: String, write: suspend (OutputStream) -> Unit) {
        var finished = false
        try {
            directory.openOutput(file).use { output -> write(output) }
            finished = true
        } finally {
            if (!finished) {
                // Best effort: a provider that refuses leaves a partial file, which the next export
                // replaces because its size is wrong. `delete` does not suspend, so this cannot
                // swallow a cancellation.
                runCatching { directory.delete(file) }.onFailure { failure ->
                    crashReporter.recordNonFatal("Episode export left a partial file", failure)
                }
            }
        }
    }

    /**
     * Copies this stream to [output], checking between chunks whether the export was cancelled.
     *
     * An episode is hundreds of megabytes; a copy that ignored cancellation would keep a stopped
     * worker writing for minutes.
     */
    private suspend fun InputStream.copyCancellably(output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    /**
     * Reads up to [count] bytes without consuming them.
     *
     * @return the bytes read; fewer than [count] only when the stream is shorter.
     */
    private fun BufferedInputStream.peek(count: Int): ByteArray {
        mark(count)
        val header = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(header, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        reset()
        return header.copyOf(filled)
    }

    /**
     * What copying the selection came to.
     *
     * @property summary the counts the user is shown.
     * @property inFolderIds the episodes whose audio is in the folder, written now or found there.
     */
    private data class CopyResult(val summary: ExportSummary, val inFolderIds: List<String>)

    /** What became of one episode. */
    private enum class Outcome { EXPORTED, ALREADY_THERE, FAILED }

    private companion object {
        /** Chunk size for the copy, and the input buffer the header is peeked through. */
        const val COPY_BUFFER_BYTES = 64 * 1024

        /** The list's type; the same one the downloads screen writes its list as. */
        const val LIST_MIME_TYPE = "text/markdown"

        /**
         * States a download is asked for from: never tried, tried and failed, or queued — which may
         * be a request Media3 never received; see `awaitDownloads`.
         */
        val REQUESTABLE_STATES =
            setOf(DownloadState.NOT_DOWNLOADED, DownloadState.FAILED, DownloadState.QUEUED)

        /** Request-and-wait rounds: the first, and one more for what a racing writer undid. */
        const val DOWNLOAD_ROUNDS = 2

        /** States that mean a download is still on its way. */
        val PENDING_STATES = setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)
    }
}

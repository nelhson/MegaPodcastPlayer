package md.borisveriga.megapodcastplayer.core.data.export

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.media.download.DownloadedAudioReader
import md.borisveriga.megapodcastplayer.core.model.AudioContainer
import md.borisveriga.megapodcastplayer.core.model.exportFileName
import md.borisveriga.megapodcastplayer.core.model.exportFileNameWithoutPosition
import md.borisveriga.megapodcastplayer.core.model.exportFolderName

/**
 * Copies one show's downloaded episodes out of the download cache into a folder the user picked.
 *
 * What the user gets is a folder named after the show, holding one ordinary audio file per episode,
 * numbered in the show's own order: `Show/001 - First talk.m4a`. That is a playlist any other player,
 * file manager or car stereo can read, which a download inside Media3's cache is not.
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
 * Exporting the same show twice therefore picks up where an interrupted run stopped, and adds only
 * what was downloaded since. A copy that fails or is cancelled deletes its partial file straight
 * away, and the size check catches the ones that could not be deleted.
 *
 * @property podcastDao reads the show's title for the folder name.
 * @property episodeDao lists the show's finished downloads, in export order.
 * @property reader reads each episode's audio back out of the cache.
 * @property directory the picked folder.
 * @property crashReporter told about each episode that could not be written, which the user only
 *   sees as a count.
 * @property ioDispatcher everything here blocks on disk.
 */
@Singleton
class EpisodeAudioExporter @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val reader: DownloadedAudioReader,
    private val directory: ExportDirectory,
    private val crashReporter: CrashReporter,
    @param:Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Exports every finished download of one show.
     *
     * @param podcastId the show.
     * @param treeUri the folder the user picked.
     * @param onProgress told after each episode, and once before the first.
     * @return what happened to each episode, or the failure that stopped the export reaching the
     *   folder at all. A show that no longer exists is a failure too.
     */
    suspend fun export(
        podcastId: String,
        treeUri: String,
        onProgress: suspend (ExportProgress) -> Unit,
    ): Result<ExportSummary> = withContext(ioDispatcher) {
        suspendRunCatching {
            val podcast = checkNotNull(podcastDao.getById(podcastId)) { "No show $podcastId" }
            val episodes = episodeDao.getDownloadedForExport(podcastId)
            onProgress(ExportProgress(done = 0, total = episodes.size))

            val folder = directory.findOrCreateFolder(
                parent = directory.root(treeUri),
                name = exportFolderName(podcast.title),
            )
            val existing = directory.files(folder).toMutableList()

            var summary = ExportSummary(exported = 0, alreadyThere = 0, failed = 0)
            episodes.forEachIndexed { index, episode ->
                summary = when (exportOne(folder, existing, episode, index + 1, episodes.size)) {
                    Outcome.EXPORTED -> summary.copy(exported = summary.exported + 1)
                    Outcome.ALREADY_THERE -> summary.copy(alreadyThere = summary.alreadyThere + 1)
                    Outcome.FAILED -> summary.copy(failed = summary.failed + 1)
                }
                onProgress(ExportProgress(done = index + 1, total = episodes.size))
            }
            summary
        }.onFailure { failure ->
            crashReporter.recordNonFatal("Episode audio export could not start", failure)
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

    /** What became of one episode. */
    private enum class Outcome { EXPORTED, ALREADY_THERE, FAILED }

    private companion object {
        /** Chunk size for the copy, and the input buffer the header is peeked through. */
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

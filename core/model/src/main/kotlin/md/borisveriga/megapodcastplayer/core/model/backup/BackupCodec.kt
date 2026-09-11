package md.borisveriga.megapodcastplayer.core.model.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes [BackupFile] handovers.
 *
 * Nobody picks one of these out of a document provider — [OpmlCodec] handles the file the user
 * actually chooses. This encodes the subscriptions that import produced so they can be left in the
 * cache for a worker to pick up, and decodes them at the other end.
 *
 * Both ends therefore ship from one build, which is why this decodes as strictly as everything else
 * here: an unknown key in a handover is a corrupted file or a leftover from an install that is no
 * longer running, and either way there is nothing to salvage. It used to be lenient because the
 * document was a user's backup that had to survive a version change; there is no such document now.
 */
object BackupCodec {

    private val json = Json {
        // The handover is written to disk and read back by another process, so it is worth being
        // able to open when a restore goes wrong.
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Serialises [file] to the text left in the cache for the restorer.
     *
     * @param file the subscriptions to hand over.
     * @return pretty-printed JSON.
     */
    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /**
     * Parses a handover written by [encode].
     *
     * Never throws: the file has been on disk since before the process that reads it existed, so a
     * truncated or stale one is an expected outcome with an answer rather than an error to report.
     *
     * @param text the document's full contents.
     * @return what the document turned out to be.
     */
    fun decode(text: String): BackupDecodeResult {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            return BackupDecodeResult.Malformed(e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization raises this for structurally valid JSON of the wrong shape.
            return BackupDecodeResult.Malformed(e.message.orEmpty())
        }
        return BackupDecodeResult.Decoded(file)
    }
}

/** What [BackupCodec.decode] made of a handover. */
sealed interface BackupDecodeResult {

    /**
     * The document is a list of subscriptions to restore.
     *
     * @property file the decoded document.
     */
    data class Decoded(val file: BackupFile) : BackupDecodeResult

    /**
     * The document is not one of ours — truncated, or written by an install long gone.
     *
     * @property reason the parser's own description, for a log rather than for the user.
     */
    data class Malformed(val reason: String) : BackupDecodeResult
}

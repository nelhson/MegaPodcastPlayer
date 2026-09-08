package md.borisveriga.megapodcastplayer.core.model.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes [BackupFile] documents.
 *
 * ## Why this codec is lenient when the rest of the app is strict
 *
 * Everything else that serialises here — the Wear protocol above all — decodes strictly, because
 * both ends ship from one build and an unknown field is therefore corruption rather than a newer
 * peer. A backup file has no second end that ships from one build. It is written by one install and
 * read by another, possibly months later, possibly by a newer app, and specifically after the wipe
 * it exists to survive. That is the one case the project's no-compatibility rule excludes, so this
 * codec — and only this codec — tolerates unknown keys and carries a version.
 *
 * Tolerance is not the same as guessing, though: a document from a *newer* writer is refused
 * outright rather than decoded with [Json.ignoreUnknownKeys] quietly dropping whatever that writer
 * considered essential. Silently restoring three quarters of a library is worse than declining.
 */
object BackupCodec {

    private val json = Json {
        // A newer writer's extra field must cost the user a warning, never their library.
        ignoreUnknownKeys = true
        // The file is meant to be opened and read; an omitted empty list is a puzzle, not a saving.
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Serialises [file] to the text written to the user's chosen document.
     *
     * @param file the document to write.
     * @return pretty-printed JSON.
     */
    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /**
     * Parses [text] that the user picked from their filesystem.
     *
     * Never throws: the input is an arbitrary file chosen through a document picker, so every
     * failure mode is an expected outcome with a message rather than an error to report.
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
        return if (file.version > CURRENT_BACKUP_VERSION) {
            BackupDecodeResult.TooNew(file.version)
        } else {
            BackupDecodeResult.Decoded(file)
        }
    }
}

/** What [BackupCodec.decode] made of a file the user picked. */
sealed interface BackupDecodeResult {

    /**
     * The document is a backup this build can restore.
     *
     * @property file the decoded document. A version older than the current one has already had
     *   its missing fields filled from their defaults.
     */
    data class Decoded(val file: BackupFile) : BackupDecodeResult

    /**
     * The document is a backup, but from a build that knows fields this one does not.
     *
     * @property version the version the document declares.
     */
    data class TooNew(val version: Int) : BackupDecodeResult

    /**
     * The document is not a backup at all — the wrong file, or a truncated one.
     *
     * @property reason the parser's own description, for a log rather than for the user.
     */
    data class Malformed(val reason: String) : BackupDecodeResult
}

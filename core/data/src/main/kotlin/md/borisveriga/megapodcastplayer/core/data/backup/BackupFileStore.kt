package md.borisveriga.megapodcastplayer.core.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching

/**
 * Reads and writes the document the user picked through the Storage Access Framework.
 *
 * Text rather than streams: a backup is tens of kilobytes even for a large library, and holding it
 * whole is what lets a picked file be validated before anything acts on it.
 *
 * @property context used only to reach the content resolver.
 * @property crashReporter told when a document cannot be opened. The user gets a message, but the
 *   underlying failure — a revoked grant, a provider that died — is otherwise invisible.
 * @property ioDispatcher dispatcher for the file work.
 */
@Singleton
class BackupFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashReporter: CrashReporter,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Writes [text] to [uri].
     *
     * @param uri a document the user created through the picker.
     * @param text the encoded backup.
     * @return success, or the failure that stopped it.
     */
    suspend fun write(uri: Uri, text: String): Result<Unit> = withContext(ioDispatcher) {
        suspendRunCatching {
            val stream = context.contentResolver.openOutputStream(uri, WRITE_TRUNCATE)
                ?: throw IOException("No output stream for $uri")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.onFailure { failure ->
            crashReporter.recordNonFatal("Backup export write failed", failure)
        }
    }

    /**
     * Reads the whole of [uri].
     *
     * @param uri a document the user picked.
     * @return the document's text, or the failure that stopped it.
     */
    suspend fun read(uri: Uri): Result<String> = withContext(ioDispatcher) {
        suspendRunCatching {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("No input stream for $uri")
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.onFailure { failure ->
            crashReporter.recordNonFatal("Backup import read failed", failure)
        }
    }

    private companion object {
        /**
         * Truncate on open.
         *
         * A picker asked to create a document may hand back one that already exists, and the
         * default `"w"` mode is not guaranteed to shorten it — a smaller export written over a
         * larger one would otherwise leave a tail of the old file behind and produce a document
         * that no longer parses.
         */
        const val WRITE_TRUNCATE = "wt"
    }
}

package md.borisveriga.megapodcastplayer.core.data.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The folder an export writes into, as far as the export needs to know about it.
 *
 * Every location is an opaque string. The real implementation's strings are document URIs inside a
 * tree the user picked, but nothing in the export loop depends on that, which is what lets the loop
 * be tested against a map in memory instead of a storage provider.
 *
 * Every method blocks; call them off the main thread.
 */
interface ExportDirectory {

    /**
     * The picked folder itself.
     *
     * @param treeUri what the folder picker returned.
     * @return the location of the folder, for [findOrCreateFolder].
     */
    fun root(treeUri: String): String

    /**
     * Finds a folder by name inside [parent], creating it if there is none.
     *
     * Found rather than always created, so exporting the same show twice fills one folder instead
     * of making `Show (1)` beside `Show`.
     *
     * @param parent the folder to look in.
     * @param name the folder's display name.
     * @return its location.
     */
    fun findOrCreateFolder(parent: String, name: String): String

    /**
     * Everything directly inside [folder].
     *
     * @param folder the folder to list.
     * @return one entry per child.
     */
    fun files(folder: String): List<ExportedFile>

    /**
     * Creates an empty file.
     *
     * @param folder where to create it.
     * @param name its display name, extension included.
     * @param mimeType what it holds. An implementation may prefer the type its storage associates
     *   with the name's extension, so the storage does not add an extension of its own.
     * @return the new file's location.
     */
    fun createFile(folder: String, name: String, mimeType: String): String

    /**
     * Opens a file created by [createFile] for writing.
     *
     * @param file the file's location.
     * @return a stream the caller closes.
     */
    fun openOutput(file: String): OutputStream

    /**
     * Deletes a file: what is left of a copy that did not finish.
     *
     * @param file the file's location.
     */
    fun delete(file: String)
}

/**
 * One file already in an export folder.
 *
 * @property location where it is, for [ExportDirectory.delete].
 * @property name its display name.
 * @property sizeBytes its size, or null when the storage does not say.
 */
data class ExportedFile(val location: String, val name: String, val sizeBytes: Long?)

/**
 * [ExportDirectory] over a Storage Access Framework tree.
 *
 * Plain [DocumentsContract] rather than `androidx.documentfile`: this is a handful of calls, and a
 * new dependency would need its verification metadata refreshed for nothing the platform does not
 * already give.
 *
 * Writing needs a *persistable* grant on the tree, taken when the export is started, because the
 * work runs after the picker's activity result, possibly long after, in a worker.
 *
 * @property context used only to reach the content resolver.
 */
@Singleton
class DocumentTreeExportDirectory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ExportDirectory {

    override fun root(treeUri: String): String {
        val tree = treeUri.toUri()
        return DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        ).toString()
    }

    override fun findOrCreateFolder(parent: String, name: String): String {
        val parentUri = parent.toUri()
        children(parentUri)
            .firstOrNull { it.name == name && it.mimeType == Document.MIME_TYPE_DIR }
            ?.let { return it.uri.toString() }
        return create(parentUri, Document.MIME_TYPE_DIR, name)
    }

    override fun files(folder: String): List<ExportedFile> =
        children(folder.toUri())
            .filter { it.mimeType != Document.MIME_TYPE_DIR }
            .map { ExportedFile(it.uri.toString(), it.name, it.sizeBytes) }

    override fun createFile(folder: String, name: String, mimeType: String): String {
        // The file-system provider appends the extension it associates with the MIME type when the
        // name's own differs: `audio/webm` would turn `001 - Talk.webm` into `001 - Talk.webm.weba`,
        // a name the next export would not recognise. The type the platform gives the extension
        // cannot disagree with it.
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val platformType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return create(folder.toUri(), platformType ?: mimeType, name)
    }

    override fun openOutput(file: String): OutputStream =
        context.contentResolver.openOutputStream(file.toUri(), WRITE_TRUNCATE)
            ?: throw FileNotFoundException("No output stream for $file")

    override fun delete(file: String) {
        DocumentsContract.deleteDocument(context.contentResolver, file.toUri())
    }

    /**
     * Creates a document.
     *
     * @return its URI.
     * @throws FileNotFoundException when the provider refuses.
     */
    private fun create(parent: Uri, mimeType: String, name: String): String =
        DocumentsContract.createDocument(context.contentResolver, parent, mimeType, name)
            ?.toString()
            ?: throw FileNotFoundException("Could not create $name in $parent")

    /**
     * Lists the documents directly inside a folder.
     *
     * @param folder a document URI built from a tree, which is the only kind this class hands out.
     * @return one entry per child.
     */
    private fun children(folder: Uri): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            folder,
            DocumentsContract.getDocumentId(folder),
        )
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        Child(
                            uri = DocumentsContract.buildDocumentUriUsingTree(
                                folder,
                                it.getString(COLUMN_ID),
                            ),
                            name = it.getString(COLUMN_NAME).orEmpty(),
                            mimeType = it.getString(COLUMN_MIME).orEmpty(),
                            sizeBytes = if (it.isNull(COLUMN_SIZE)) null else it.getLong(COLUMN_SIZE),
                        ),
                    )
                }
            }
        }
    }

    /**
     * One row of a folder listing.
     *
     * @property uri the child's document URI.
     * @property name its display name.
     * @property mimeType its type; [Document.MIME_TYPE_DIR] for a folder.
     * @property sizeBytes its size, or null when the provider does not report one.
     */
    private data class Child(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long?,
    )

    private companion object {
        /** Truncate on open, as `BackupFileStore` does, so no tail of an older file survives. */
        const val WRITE_TRUNCATE = "wt"

        /** Positions of the listing's columns, in the order the projection names them. */
        const val COLUMN_ID = 0
        const val COLUMN_NAME = 1
        const val COLUMN_MIME = 2
        const val COLUMN_SIZE = 3
    }
}

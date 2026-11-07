package md.borisveriga.megapodcastplayer.core.model

/**
 * How the downloads screen is narrowed: which folder it shows and which kind of download.
 *
 * Stored, so the screen comes back as it was left. Someone who filters the list to one folder and
 * leaves to play an episode expects that folder on their return, not everything again; the choice
 * is theirs and the screen's job is to keep it. The default is the widest view, which is what a
 * fresh install opens with.
 *
 * A folder that has since been deleted is still stored as chosen; the screen falls back to every
 * folder when it reads one, see [FolderView.orAllFoldersIfGone].
 *
 * @property kind which kind of download is listed.
 * @property folder which folder is listed.
 */
data class DownloadsView(
    val kind: DownloadKindFilter = DownloadKindFilter.ALL,
    val folder: FolderView = FolderView.AllFolders,
) {
    companion object {
        /** Every folder and every kind: what the screen opens with before any choice is made. */
        val DEFAULT = DownloadsView()
    }
}

/**
 * Writes a [FolderView] as one short string and reads it back.
 *
 * Its own encoding rather than JSON because a folder view is two words or an id: `all`, `downloads`,
 * or `folder:` followed by the folder's id. A folder id is a UUID and never holds a colon, so the
 * prefix is unambiguous.
 */
object FolderViewCodec {

    private const val ALL = "all"
    private const val DOWNLOADS = "downloads"
    private const val FOLDER_PREFIX = "folder:"

    /**
     * Encodes a view for storage.
     *
     * @param view what to store.
     * @return the string.
     */
    fun encode(view: FolderView): String = when (view) {
        FolderView.AllFolders -> ALL
        FolderView.Downloads -> DOWNLOADS
        is FolderView.Folder -> FOLDER_PREFIX + view.folderId
    }

    /**
     * Decodes a stored view.
     *
     * @param stored the string written by [encode], or null when nothing was ever stored.
     * @return the view; [FolderView.AllFolders] when nothing is stored or the string is not one
     *   this build wrote, since the widest view is the one that can never show nothing by mistake.
     */
    fun decode(stored: String?): FolderView = when {
        stored == null -> FolderView.AllFolders

        stored == ALL -> FolderView.AllFolders

        stored == DOWNLOADS -> FolderView.Downloads

        stored.startsWith(FOLDER_PREFIX) && stored.length > FOLDER_PREFIX.length ->
            FolderView.Folder(stored.removePrefix(FOLDER_PREFIX))

        else -> FolderView.AllFolders
    }
}

package md.borisveriga.megapodcastplayer.core.data.repository

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadFolder
import md.borisveriga.megapodcastplayer.core.model.DownloadFolders
import md.borisveriga.megapodcastplayer.core.model.FolderNameProblem

/**
 * The folders the user files their downloads under.
 *
 * Folders are labels kept beside the downloads, not places the downloads are kept: nothing here
 * touches the download cache, and every download is where it always was whatever folder it is in.
 * See [DownloadFolders] for the rules — the built-in *Downloads* folder, which is the `null` folder
 * id throughout, and where a new download is filed.
 *
 * Filing a download as it is asked for, and forgetting it once it is gone, are done by the
 * download repository through [file] and [forget]; screens use the rest.
 */
interface DownloadFolderRepository {

    /** Observes every folder, which download is in which, and the default. */
    fun observeFolders(): Flow<DownloadFolders>

    /**
     * Makes a new folder, after the others.
     *
     * @param name what to call it; trimmed.
     * @return the folder made, or why the name was refused.
     */
    suspend fun createFolder(name: String): FolderEdit

    /**
     * Renames a folder; its downloads stay in it.
     *
     * @param folderId the folder.
     * @param name the new name; trimmed.
     * @return the folder as renamed, or why the name was refused. [FolderEdit.Gone] when the folder
     *   no longer exists.
     */
    suspend fun renameFolder(folderId: String, name: String): FolderEdit

    /**
     * Deletes a folder. Its downloads move to *Downloads* and stay on the device.
     *
     * @param folderId the folder.
     */
    suspend fun deleteFolder(folderId: String)

    /**
     * Files downloads under a folder.
     *
     * @param episodeIds the downloads to move.
     * @param folderId where to; null for *Downloads*. A folder that no longer exists moves nothing.
     */
    suspend fun moveToFolder(episodeIds: Collection<String>, folderId: String?)

    /**
     * Sets where a download goes when nobody says.
     *
     * @param folderId the folder; null for *Downloads*.
     */
    suspend fun setDefaultFolder(folderId: String?)

    /**
     * Files a download that is being asked for.
     *
     * @param episodeId the episode being downloaded.
     * @param destination where the caller asked for it to go.
     */
    suspend fun file(episodeId: String, destination: DownloadDestination)

    /**
     * Forgets which folder downloads were in, because they have been removed.
     *
     * @param episodeIds the episodes whose downloads are gone.
     */
    suspend fun forget(episodeIds: Collection<String>)

    /** Forgets every download's folder, because every download has been removed. Keeps the folders. */
    suspend fun forgetAll()
}

/** The outcome of making or renaming a folder. */
sealed interface FolderEdit {

    /**
     * The folder as it now is.
     *
     * @property folder the folder made or renamed.
     */
    data class Done(val folder: DownloadFolder) : FolderEdit

    /**
     * Nothing changed, because the name was not acceptable.
     *
     * @property problem what was wrong with it.
     */
    data class Refused(val problem: FolderNameProblem) : FolderEdit

    /** Nothing changed, because the folder was deleted before the rename arrived. */
    data object Gone : FolderEdit
}

/**
 * DataStore-backed [DownloadFolderRepository].
 *
 * Every change is one read-modify-write of the stored [DownloadFolders], so a name is judged
 * against the folders as they are at the moment of writing rather than as a screen last saw them —
 * two dialogs cannot both make a "Commute".
 *
 * @property userPreferences the preferences file the folders live in.
 */
@Singleton
class DefaultDownloadFolderRepository @Inject constructor(
    private val userPreferences: UserPreferencesDataSource,
) : DownloadFolderRepository {

    override fun observeFolders(): Flow<DownloadFolders> = userPreferences.downloadFolders

    override suspend fun createFolder(name: String): FolderEdit {
        val id = UUID.randomUUID().toString()
        var outcome: FolderEdit = FolderEdit.Gone
        userPreferences.updateDownloadFolders { folders ->
            val problem = folders.nameProblem(name)
            if (problem != null) {
                outcome = FolderEdit.Refused(problem)
                folders
            } else {
                folders.created(id, name).also { outcome = FolderEdit.Done(it.folders.last()) }
            }
        }
        return outcome
    }

    override suspend fun renameFolder(folderId: String, name: String): FolderEdit {
        var outcome: FolderEdit = FolderEdit.Gone
        userPreferences.updateDownloadFolders { folders ->
            val problem = folders.nameProblem(name, ignoringId = folderId)
            when {
                !folders.exists(folderId) -> folders.also { outcome = FolderEdit.Gone }

                problem != null -> folders.also { outcome = FolderEdit.Refused(problem) }

                else -> folders.renamed(folderId, name).also { renamed ->
                    outcome = FolderEdit.Done(renamed.folders.first { it.id == folderId })
                }
            }
        }
        return outcome
    }

    override suspend fun deleteFolder(folderId: String) {
        userPreferences.updateDownloadFolders { it.deleted(folderId) }
    }

    override suspend fun moveToFolder(episodeIds: Collection<String>, folderId: String?) {
        userPreferences.updateDownloadFolders { it.moved(episodeIds, folderId) }
    }

    override suspend fun setDefaultFolder(folderId: String?) {
        userPreferences.updateDownloadFolders { it.withDefault(folderId) }
    }

    override suspend fun file(episodeId: String, destination: DownloadDestination) {
        userPreferences.updateDownloadFolders { it.assigned(episodeId, destination) }
    }

    override suspend fun forget(episodeIds: Collection<String>) {
        userPreferences.updateDownloadFolders { it.forgotten(episodeIds) }
    }

    override suspend fun forgetAll() {
        userPreferences.updateDownloadFolders { it.copy(membership = emptyMap()) }
    }
}

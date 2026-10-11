package md.borisveriga.megapodcastplayer.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A folder the user made for their downloads.
 *
 * A label, not a directory: the bytes of every download stay in the one download cache, keyed as
 * they always were, and a folder is only which name the downloads screen files an episode under.
 * That is what lets folders be created, renamed and emptied without a byte of audio or video
 * moving, and why nothing about them can orphan a download.
 *
 * @property id minted once when the folder is made and never derived from [name], so a rename keeps
 *   every episode that was in it.
 * @property name what the user called it; see [DownloadFolders.nameProblem] for what is accepted.
 */
@Serializable
data class DownloadFolder(
    val id: String,
    val name: String,
)

/**
 * Why a folder name was refused.
 *
 * A value rather than an exception: a name typed into a dialog that is not acceptable is the
 * ordinary case of that dialog, and the screen says which of these it was.
 */
enum class FolderNameProblem {

    /** Nothing but spaces. */
    BLANK,

    /** Longer than [DownloadFolders.MAX_NAME_LENGTH]: it would not fit on a chip. */
    TOO_LONG,

    /** Another folder already has it, ignoring case: two "Commute"s would be two of one thing. */
    TAKEN,
}

/**
 * Where a download should be filed when it is asked for.
 *
 * Three cases rather than a nullable folder id, because "the user did not say" and "the user said
 * the built-in Downloads folder" are different requests: the first defers to a folder the episode
 * is already in and then to the default, the second does not.
 */
sealed interface DownloadDestination {

    /** No choice was made — a swipe, a retry, an auto-download. */
    data object Unspecified : DownloadDestination

    /** The built-in Downloads folder, chosen on purpose. */
    data object Downloads : DownloadDestination

    /**
     * A folder the user made.
     *
     * @property folderId the folder's [DownloadFolder.id].
     */
    data class Folder(val folderId: String) : DownloadDestination
}

/**
 * Every download folder, which episode is in which, and where new downloads go.
 *
 * Every download is in exactly one folder: the built-in *Downloads* folder unless [membership]
 * says otherwise. *Downloads* has no entry in [folders] and no id — it is the `null` folder
 * throughout — which is what makes it impossible to rename or delete, and what an episode falls
 * back to when the folder it was in is gone.
 *
 * An episode's audio and its video are one download here: the screen draws them as one row, and a
 * video is never on the device without its sound.
 *
 * Every operation returns a new value and leaves this one alone, so the whole of it can be stored
 * under one key and changed in one atomic edit.
 *
 * @property folders the folders the user made, in the order they were made.
 * @property membership episode id to folder id, for episodes outside *Downloads*. An entry naming a
 *   folder that no longer exists is read as *Downloads* by [folderOf] rather than trusted.
 * @property defaultFolderId where a download with no destination of its own goes; null for
 *   *Downloads*.
 */
@Serializable
data class DownloadFolders(
    val folders: List<DownloadFolder> = emptyList(),
    val membership: Map<String, String> = emptyMap(),
    val defaultFolderId: String? = null,
) {

    /**
     * The folder an episode is filed under.
     *
     * @param episodeId the episode.
     * @return its folder's id, or null for *Downloads*.
     */
    fun folderOf(episodeId: String): String? = membership[episodeId]?.takeIf(::exists)

    /**
     * Whether a folder id names a folder that exists.
     *
     * @param folderId the id to look for.
     * @return true when it is one of [folders].
     */
    fun exists(folderId: String): Boolean = folders.any { it.id == folderId }

    /**
     * What would be wrong with a name, if anything.
     *
     * Trimmed before it is judged, so "Commute " and "Commute" are the same name.
     *
     * @param name the name typed.
     * @param ignoringId the folder being renamed, whose own name does not count as taken; null when
     *   creating.
     * @return the problem, or null when the name is acceptable.
     */
    fun nameProblem(name: String, ignoringId: String? = null): FolderNameProblem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> FolderNameProblem.BLANK

            trimmed.length > MAX_NAME_LENGTH -> FolderNameProblem.TOO_LONG

            folders.any { it.id != ignoringId && it.name.equals(trimmed, ignoreCase = true) } ->
                FolderNameProblem.TAKEN

            else -> null
        }
    }

    /**
     * Adds a folder at the end.
     *
     * @param id the new folder's id; minted by the caller so this stays a pure function.
     * @param name its name; must pass [nameProblem].
     * @return the folders with the new one added.
     * @throws IllegalArgumentException when [name] is not acceptable or [id] is already used.
     */
    fun created(id: String, name: String): DownloadFolders {
        require(nameProblem(name) == null) { "Unacceptable folder name" }
        require(!exists(id)) { "Folder id already used" }
        return copy(folders = folders + DownloadFolder(id, name.trim()))
    }

    /**
     * Renames a folder; its episodes stay in it.
     *
     * @param id the folder.
     * @param name the new name; must pass [nameProblem] for this folder.
     * @return the folders with the one renamed, or this unchanged when [id] does not exist.
     * @throws IllegalArgumentException when [name] is not acceptable.
     */
    fun renamed(id: String, name: String): DownloadFolders {
        if (!exists(id)) return this
        require(nameProblem(name, ignoringId = id) == null) { "Unacceptable folder name" }
        return copy(folders = folders.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    /**
     * Deletes a folder. Its episodes move to *Downloads*; nothing is deleted from the device.
     *
     * When it was the default, new downloads go to *Downloads* again.
     *
     * @param id the folder.
     * @return the folders without it.
     */
    fun deleted(id: String): DownloadFolders = copy(
        folders = folders.filterNot { it.id == id },
        membership = membership.filterValues { it != id },
        defaultFolderId = defaultFolderId.takeUnless { it == id },
    )

    /**
     * Files episodes under a folder.
     *
     * @param episodeIds the episodes to move.
     * @param folderId where to; null for *Downloads*.
     * @return the folders with the episodes moved, or this unchanged when [folderId] does not exist.
     */
    fun moved(episodeIds: Collection<String>, folderId: String?): DownloadFolders = when {
        folderId == null -> copy(membership = membership - episodeIds.toSet())
        !exists(folderId) -> this
        else -> copy(membership = membership + episodeIds.associateWith { folderId })
    }

    /**
     * Sets where downloads go when nobody says.
     *
     * @param folderId the folder; null for *Downloads*.
     * @return the folders with the new default, or this unchanged when [folderId] does not exist.
     */
    fun withDefault(folderId: String?): DownloadFolders =
        if (folderId == null || exists(folderId)) copy(defaultFolderId = folderId) else this

    /**
     * Forgets which folder episodes were in, because their downloads are gone.
     *
     * Not kept for a later download the way the downloads order keeps an id: a deleted episode
     * downloaded again weeks later is a new download, and it belongs where new downloads go — not
     * in a folder the user may long have stopped using for it.
     *
     * @param episodeIds the episodes whose downloads were removed.
     * @return the folders without those memberships.
     */
    fun forgotten(episodeIds: Collection<String>): DownloadFolders =
        if (episodeIds.none { it in membership }) this else copy(membership = membership - episodeIds.toSet())

    /**
     * Which folder a download being asked for should be filed under.
     *
     * In order: the folder asked for; then, when nothing was asked, the folder the episode is
     * already in — a video joining its audio, or a failed download tried again, stays where it
     * was — and then the default. A folder that no longer exists falls through to the next rule.
     *
     * @param episodeId the episode being downloaded.
     * @param destination what the caller asked for.
     * @return the folder's id, or null for *Downloads*.
     */
    fun folderForDownload(episodeId: String, destination: DownloadDestination): String? =
        when (destination) {
            DownloadDestination.Downloads -> null

            is DownloadDestination.Folder ->
                destination.folderId.takeIf(::exists) ?: folderForDownload(episodeId, DownloadDestination.Unspecified)

            DownloadDestination.Unspecified ->
                membership[episodeId]?.takeIf(::exists) ?: defaultFolderId?.takeIf(::exists)
        }

    /**
     * Files a download being asked for; see [folderForDownload] for where it goes.
     *
     * @param episodeId the episode being downloaded.
     * @param destination what the caller asked for.
     * @return the folders with the episode filed.
     */
    fun assigned(episodeId: String, destination: DownloadDestination): DownloadFolders =
        moved(listOf(episodeId), folderForDownload(episodeId, destination))

    companion object {
        /** The longest folder name accepted: about what a chip shows before it truncates. */
        const val MAX_NAME_LENGTH = 40

        /** No folders, everything in *Downloads*: what a fresh install has. */
        val NONE = DownloadFolders()
    }
}

/**
 * Stores [DownloadFolders] as one JSON value.
 *
 * Strict, like every other decoder in the app: the app that reads this wrote it. A value that does
 * not decode becomes [DownloadFolders.NONE] — every download back in *Downloads* — rather than an
 * error on the downloads screen. That loses the folders, which is a real loss; it is also only
 * reachable through a corrupt preferences file, where it is the least of what is lost.
 */
object DownloadFoldersCodec {

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    /**
     * Encodes the folders for storage.
     *
     * @param folders what to store.
     * @return the JSON.
     */
    fun encode(folders: DownloadFolders): String = json.encodeToString(folders)

    /**
     * Decodes stored folders.
     *
     * @param stored the JSON written by [encode], or null when nothing was ever stored.
     * @return the folders; [DownloadFolders.NONE] when nothing is stored or it does not decode.
     */
    fun decode(stored: String?): DownloadFolders {
        if (stored.isNullOrEmpty()) return DownloadFolders.NONE
        return try {
            json.decodeFromString<DownloadFolders>(stored)
        } catch (_: SerializationException) {
            DownloadFolders.NONE
        } catch (_: IllegalArgumentException) {
            DownloadFolders.NONE
        }
    }
}

/**
 * Which folder the downloads screen is showing.
 *
 * The screen's own choice, not a [DownloadDestination]: "every folder at once" is a way of looking
 * that has no place a download could be filed.
 */
sealed interface FolderView {

    /** Every download, whatever folder it is in. What the screen opens with. */
    data object AllFolders : FolderView

    /** The built-in *Downloads* folder: everything not filed anywhere else. */
    data object Downloads : FolderView

    /**
     * One folder the user made.
     *
     * @property folderId the folder's [DownloadFolder.id].
     */
    data class Folder(val folderId: String) : FolderView

    /**
     * Whether a download filed under [folderId] is in this view.
     *
     * @param folderId the download's folder; null for *Downloads*.
     * @return true when the row is shown.
     */
    fun contains(folderId: String?): Boolean = when (this) {
        AllFolders -> true
        Downloads -> folderId == null
        is Folder -> folderId == this.folderId
    }

    /**
     * This view, or [AllFolders] when it names a folder that has since been deleted — a screen left
     * on a folder that no longer exists would show an empty list with no way to say why.
     *
     * @param folders the folders as they are now.
     * @return a view that can be shown.
     */
    fun orAllFoldersIfGone(folders: DownloadFolders): FolderView =
        if (this is Folder && !folders.exists(folderId)) AllFolders else this
}

/**
 * Narrows the tracked downloads to one folder.
 *
 * @param view the folder to show.
 * @param folders which download is in which folder.
 * @return the downloads in it, in the order they arrived.
 */
fun List<EpisodeWithShow>.inFolder(view: FolderView, folders: DownloadFolders): List<EpisodeWithShow> =
    if (view == FolderView.AllFolders) this else filter { view.contains(folders.folderOf(it.episode.id)) }

/**
 * How many of the tracked downloads are in each folder.
 *
 * @param folders which download is in which folder.
 * @return the count per folder id, with *Downloads* under null; a folder with nothing in it is
 *   absent rather than zero.
 */
fun List<EpisodeWithShow>.countByFolder(folders: DownloadFolders): Map<String?, Int> =
    groupingBy { folders.folderOf(it.episode.id) }.eachCount()

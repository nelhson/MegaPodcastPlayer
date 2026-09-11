package md.borisveriga.megapodcastplayer.core.data.repository

import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile

/**
 * How far a restore has got, for a screen that has to sit through minutes of network work.
 *
 * @property completed shows finished so far, successful or not.
 * @property total shows in the backup.
 * @property currentTitle the show being fetched right now, as the backup names it.
 */
data class RestoreProgress(
    val completed: Int,
    val total: Int,
    val currentTitle: String,
)

/**
 * What a finished restore did.
 *
 * Every show in the backup lands in exactly one of [showsRestored] or [failedTitles]; a tally that
 * does not add up is a bug, in the same way [RefreshSummary]'s is.
 *
 * @property showsRestored shows successfully added or already present.
 * @property failedTitles shows whose feed could not be fetched, by the title the file recorded.
 */
data class RestoreSummary(
    val showsRestored: Int = 0,
    val failedTitles: List<String> = emptyList(),
)

/**
 * Reads the library's subscriptions out to a document the user keeps, and puts them back.
 *
 * This exists because the database has no migrations: a schema change recreates every table, so
 * without a file the user controls, a release that adds a column costs them every show they had
 * added. What it saves is the list of shows — the links — and nothing derived from fetching them.
 */
interface BackupRepository {

    /**
     * Observes when the subscriptions were last exported, or null if they never have been.
     *
     * The settings screen shows it so that "not exported yet" is visible *before* a release that
     * changes the schema wipes the database — a warning the user cannot see in time is no warning.
     */
    fun observeLastBackupAt(): Flow<Long?>

    /**
     * Records that an export was written successfully.
     *
     * @param exportedAtMs when it was written, epoch milliseconds.
     */
    suspend fun recordExported(exportedAtMs: Long)

    /**
     * Observes the id of the restore run whose result has already been reported to the user.
     *
     * A finished run is retained and replayed by whatever ran it, so without this the settings
     * screen would announce the same restore every time it is opened.
     */
    fun observeAcknowledgedRestoreId(): Flow<String?>

    /**
     * Records that a finished restore's result has been shown.
     *
     * @param runId the run, as `RestoreRun.Finished` identifies it.
     */
    suspend fun acknowledgeRestore(runId: String)

    /**
     * Reads the current library's subscriptions into a document.
     *
     * @return the document, ready to be encoded and written.
     */
    suspend fun export(): BackupFile

    /**
     * Subscribes to every show in [file].
     *
     * Merges rather than replaces: a show already present is left exactly as it is, episodes,
     * positions and all. Adding a link the library already holds is not an event.
     *
     * A show whose feed cannot be fetched is counted and the run continues. One dead feed must not
     * cost the user the other nineteen.
     *
     * @param file the decoded document.
     * @param onProgress called once per show, on the calling coroutine.
     * @return what the run managed to do.
     */
    suspend fun restore(
        file: BackupFile,
        onProgress: (RestoreProgress) -> Unit = {},
    ): RestoreSummary
}

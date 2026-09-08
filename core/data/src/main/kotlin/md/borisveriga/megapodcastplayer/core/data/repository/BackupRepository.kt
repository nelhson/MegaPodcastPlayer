package md.borisveriga.megapodcastplayer.core.data.repository

import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile

/**
 * What a restore should do with the parts of a backup that are not simply subscriptions.
 *
 * @property reDownload re-queue every episode the backup records as downloaded. Off by default and
 *   surfaced as an explicit switch, because the audio is not in the file: acting on this list is a
 *   fresh download of potentially tens of gigabytes over whatever network happens to be connected.
 */
data class RestoreOptions(val reDownload: Boolean = false)

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
 * @property episodesRestored episodes whose stored position or played flag was re-applied.
 * @property episodesMissing episodes the backup knew about whose guid the publisher has since
 *   pruned. Expected, not an error: a feed is allowed to forget.
 * @property queueRestored queue entries written.
 * @property momentsRestored saved moments written back. Reported separately from the episode tally
 *   because a moment is the one thing in a backup the user wrote rather than earned: if some did
 *   not survive, that is the number they will want to see.
 * @property downloadsQueued downloads re-queued, always zero unless [RestoreOptions.reDownload].
 * @property failedTitles shows whose feed could not be fetched, by the title the backup recorded.
 */
data class RestoreSummary(
    val showsRestored: Int = 0,
    val episodesRestored: Int = 0,
    val episodesMissing: Int = 0,
    val queueRestored: Int = 0,
    val momentsRestored: Int = 0,
    val downloadsQueued: Int = 0,
    val failedTitles: List<String> = emptyList(),
)

/**
 * Exports the library to a document the user keeps, and puts it back.
 *
 * This exists because the database has no migrations: a schema change recreates every table, so
 * without a file the user controls, a release that adds a column costs them their subscriptions,
 * their positions and their queue.
 */
interface BackupRepository {

    /**
     * Observes when a backup was last exported, or null if one never has been.
     *
     * The settings screen shows it so that "no backup yet" is visible *before* a release that
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
     * Reads the current library into a document.
     *
     * @return the document, ready to be encoded and written.
     */
    suspend fun export(): BackupFile

    /**
     * Re-creates a library from [file].
     *
     * Merges rather than replaces — a show already present keeps its episodes and gains the
     * backup's state — with one deliberate exception: the queue is replaced wholesale, because
     * merging two orderings produces a third that is neither.
     *
     * A show whose feed cannot be fetched is counted and the run continues. One dead feed must not
     * cost the user the other nineteen.
     *
     * @param file the decoded document.
     * @param options what to do beyond restoring subscriptions and state.
     * @param onProgress called once per show, on the calling coroutine.
     * @return what the run managed to do.
     */
    suspend fun restore(
        file: BackupFile,
        options: RestoreOptions = RestoreOptions(),
        onProgress: (RestoreProgress) -> Unit = {},
    ): RestoreSummary
}

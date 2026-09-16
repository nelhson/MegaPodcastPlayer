package md.borisveriga.megapodcastplayer.core.data.export

import kotlinx.coroutines.flow.Flow

/**
 * How far an export has got.
 *
 * @property done episodes dealt with so far, whatever became of each.
 * @property total episodes the export covers.
 */
data class ExportProgress(val done: Int, val total: Int)

/**
 * What an export did, episode by episode.
 *
 * @property exported episodes written as new files.
 * @property alreadyThere episodes whose file was already in the folder from an earlier export, and
 *   were left alone. Counted apart from [exported] so that exporting twice says so.
 * @property failed episodes that could not be written, including ones the cache no longer holds in
 *   full.
 */
data class ExportSummary(val exported: Int, val alreadyThere: Int, val failed: Int)

/** How an export run is going; the same shape `RestoreRun` gives a restore. */
sealed interface ExportRun {

    /**
     * The run is under way.
     *
     * @property progress how far it has got; zero of zero while it waits to start.
     */
    data class Running(val progress: ExportProgress) : ExportRun

    /**
     * The run finished, whether or not every episode was written.
     *
     * @property summary what it managed.
     */
    data class Finished(val summary: ExportSummary) : ExportRun

    /** The run died before it could work through the episodes: no folder, or no permission. */
    data object Failed : ExportRun
}

/**
 * Exports a show's downloads somewhere that outlives the screen that asked.
 *
 * The implementation is a WorkManager worker and therefore lives in `:app`; this interface is how it
 * is handed back, as `LibraryRestorer` is for a restore. A show's worth of audio is hundreds of
 * megabytes, which is minutes of copying the user should be free to leave the app during.
 */
interface DownloadExporter {

    /**
     * Starts exporting one show's downloaded episodes into a folder.
     *
     * Does nothing new while an export of the same show is already running.
     *
     * @param podcastId the show.
     * @param treeUri the folder the user picked. The implementation takes a persistable grant on it,
     *   because the picker's own grant does not outlive the activity result.
     */
    fun start(podcastId: String, treeUri: String)

    /**
     * Observes the current or most recent export of one show.
     *
     * @param podcastId the show.
     * @return its run, or null when it has not been exported since the work history was last pruned.
     */
    fun observe(podcastId: String): Flow<ExportRun?>
}

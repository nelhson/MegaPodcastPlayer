package md.borisveriga.megapodcastplayer.core.data.export

import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter

/** Which half of a *Download and export* run is under way. */
enum class ExportStage {

    /** Waiting for the episodes that were not on the device yet to finish downloading. */
    DOWNLOADING,

    /** Copying the downloaded audio into the folder. */
    COPYING,
}

/**
 * How far an export has got.
 *
 * @property done episodes dealt with so far in this [stage]: downloaded, or copied whatever became
 *   of each.
 * @property total episodes the export covers.
 * @property stage whether the run is still downloading or already copying.
 */
data class ExportProgress(
    val done: Int,
    val total: Int,
    val stage: ExportStage = ExportStage.DOWNLOADING,
)

/**
 * What an export did, episode by episode.
 *
 * @property exported episodes written as new files.
 * @property alreadyThere episodes whose file was already in the folder from an earlier export, and
 *   were left alone. Counted apart from [exported] so that exporting twice says so.
 * @property failed episodes that could not be written, including ones that never finished
 *   downloading and ones the cache no longer holds in full.
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
 * Downloads a show's episodes and exports them somewhere that outlives the screen that asked.
 *
 * The implementation is a WorkManager worker and therefore lives in `:app`; this interface is how it
 * is handed back, as `LibraryRestorer` is for a restore. A show's worth of audio is hundreds of
 * megabytes, which is minutes of copying the user should be free to leave the app during.
 */
interface DownloadExporter {

    /**
     * Downloads the episodes of one show that the given filter lists, then copies them into a named
     * folder inside the one the user picked.
     *
     * Episodes already on the device are not downloaded again, and files already in the folder are
     * not copied again, so running it twice is cheap. Does nothing new while a run for the same show
     * is already going.
     *
     * @param podcastId the show.
     * @param treeUri the location the user picked. The implementation takes a persistable grant on
     *   it, because the picker's own grant does not outlive the activity result.
     * @param folderName the name the user gave the folder the files go into.
     * @param filter the show page's filter when the user asked, which decides the episodes.
     */
    fun start(podcastId: String, treeUri: String, folderName: String, filter: EpisodeFilter)

    /**
     * Observes the current or most recent export of one show.
     *
     * @param podcastId the show.
     * @return its run, or null when it has not been exported since the work history was last pruned.
     */
    fun observe(podcastId: String): Flow<ExportRun?>
}

package md.borisveriga.megapodcastplayer.core.data.backup

import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreProgress
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreSummary

/**
 * How a restore run is going.
 *
 * A closed set rather than a bag of nullable fields, because a screen showing both a progress
 * counter and a result dialog at once is the bug this shape prevents.
 */
sealed interface RestoreRun {

    /**
     * The run is under way.
     *
     * @property progress how far it has got.
     */
    data class Running(val progress: RestoreProgress) : RestoreRun

    /**
     * The run finished, whether or not every feed came back.
     *
     * @property summary what it managed to do.
     */
    data class Finished(val summary: RestoreSummary) : RestoreRun

    /** The run died — a corrupted handover, or a process WorkManager could not bring back. */
    data object Failed : RestoreRun
}

/**
 * Runs a restore somewhere that outlives the screen that started it.
 *
 * The implementation is a WorkManager worker and therefore lives in `:app`, which depends on this
 * module rather than the other way round. This interface is how the dependency is handed back —
 * the same arrangement `PlaybackQueueSource` uses to give `:core:media` its read side.
 *
 * Keeping WorkManager behind it also means `:feature:settings` never sees a `WorkInfo`: it gets
 * [RestoreRun], which is about restoring rather than about scheduling.
 */
interface LibraryRestorer {

    /**
     * Starts a restore.
     *
     * @param validatedJson a document the caller has already decoded successfully. Passing text
     *   rather than a picked `Uri` is deliberate: a document picker's grant does not survive to
     *   whenever the work actually runs.
     * @param reDownload whether to re-queue the downloads the backup records.
     */
    fun start(validatedJson: String, reDownload: Boolean)

    /** Observes the current or most recent run; emits null when none has run this install. */
    fun observe(): Flow<RestoreRun?>
}

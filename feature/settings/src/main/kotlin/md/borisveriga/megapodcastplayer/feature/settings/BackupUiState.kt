package md.borisveriga.megapodcastplayer.feature.settings

import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun

/**
 * Which kind of file a pending restore came out of.
 *
 * The two run through exactly the same machinery — see [PendingRestore] — and differ only in what
 * the confirmation dialog can honestly promise.
 */
enum class RestoreSource {

    /** This app's own JSON backup: shows, positions, the queue, downloads and moments. */
    BACKUP,

    /** An OPML subscription list from another app: shows, and nothing else. */
    OPML,
}

/**
 * A file the user has picked and that has already decoded cleanly, waiting to be confirmed.
 *
 * The document is held as text rather than as the picked `Uri` because the picker grants no
 * persistable access: by the time the restore actually runs, the permission may be gone. Decoding
 * before the confirmation dialog is what lets a foreign or truncated file be refused while the user
 * is still looking at the picker, rather than minutes into a run.
 *
 * **An OPML import is a restore of a backup that carries only subscriptions.** The file the user
 * picked is turned into a `BackupFile` with podcasts and nothing else, encoded, and handed to the
 * same restorer — so an import fetches feeds, reports failures by name and survives the process
 * being killed for exactly the same reasons a restore does, without a second implementation of any
 * of it. [json] is therefore always this app's own JSON, whatever the user picked.
 *
 * @property json the validated document, always JSON.
 * @property showCount how many shows it carries, so the dialog can be specific about the size of
 *   what is about to happen.
 * @property source what the user actually picked, which decides what the dialog may offer.
 * @property skipped how many rows an OPML file named that could not be used — folders and
 *   anything whose feed URL this app will not fetch. Zero for a backup, which has no such rows.
 */
data class PendingRestore(
    val json: String,
    val showCount: Int,
    val source: RestoreSource = RestoreSource.BACKUP,
    val skipped: Int = 0,
)

/**
 * Everything the backup section renders.
 *
 * @property lastBackupAtMs when a backup was last exported, or null if never — which is the state
 *   the section exists to make visible, since the database is recreated rather than migrated.
 * @property isExporting true while an export is being written, so both rows can be disabled rather
 *   than let a second tap race the first.
 * @property pendingRestore a picked, decoded document awaiting confirmation.
 * @property restore the current or most recent restore run.
 */
data class BackupUiState(
    val lastBackupAtMs: Long? = null,
    val isExporting: Boolean = false,
    val pendingRestore: PendingRestore? = null,
    val restore: RestoreRun? = null,
) {
    /** True while a restore is under way, which is when neither row should accept a tap. */
    val isRestoring: Boolean get() = restore is RestoreRun.Running

    /** True when neither backup action can be started right now. */
    val isBusy: Boolean get() = isExporting || isRestoring
}

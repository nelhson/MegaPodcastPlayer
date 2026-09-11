package md.borisveriga.megapodcastplayer.feature.settings

import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun

/**
 * A file the user has picked and that has already decoded cleanly, waiting to be confirmed.
 *
 * The document is held as text rather than as the picked `Uri` because the picker grants no
 * persistable access: by the time the import actually runs, the permission may be gone. Decoding
 * before the confirmation dialog is what lets a foreign or truncated file be refused while the user
 * is still looking at the picker, rather than minutes into a run.
 *
 * **An import is a restore of a document the user never had.** The OPML file they picked is turned
 * into a `BackupFile` of subscriptions, encoded, and handed to the same restorer that has always
 * done this — so an import fetches feeds, reports failures by name and survives the process being
 * killed, without a second implementation of any of it. [json] is therefore this app's own
 * handover format, whatever the user picked.
 *
 * @property json the validated subscriptions, as the handover JSON.
 * @property showCount how many shows it carries, so the dialog can be specific about the size of
 *   what is about to happen.
 * @property skipped how many rows the OPML file named that could not be used — folders, and
 *   anything whose feed URL this app will not fetch.
 */
data class PendingRestore(
    val json: String,
    val showCount: Int,
    val skipped: Int = 0,
)

/**
 * Everything the subscriptions section renders.
 *
 * @property lastBackupAtMs when the subscriptions were last exported, or null if never — which is
 *   the state the section exists to make visible, since the database is recreated rather than
 *   migrated.
 * @property isExporting true while an export is being written, so both rows can be disabled rather
 *   than let a second tap race the first.
 * @property pendingRestore a picked, decoded document awaiting confirmation.
 * @property restore the current or most recent import run.
 */
data class BackupUiState(
    val lastBackupAtMs: Long? = null,
    val isExporting: Boolean = false,
    val pendingRestore: PendingRestore? = null,
    val restore: RestoreRun? = null,
) {
    /** True while an import is under way, which is when neither row should accept a tap. */
    val isRestoring: Boolean get() = restore is RestoreRun.Running

    /** True when neither action can be started right now. */
    val isBusy: Boolean get() = isExporting || isRestoring
}

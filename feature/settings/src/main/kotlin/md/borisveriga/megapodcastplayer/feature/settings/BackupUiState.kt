package md.borisveriga.megapodcastplayer.feature.settings

import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun

/**
 * A backup the user has picked and that has already decoded cleanly, waiting to be confirmed.
 *
 * The document is held as text rather than as the picked `Uri` because the picker grants no
 * persistable access: by the time the restore actually runs, the permission may be gone. Decoding
 * before the confirmation dialog is what lets a foreign or truncated file be refused while the user
 * is still looking at the picker, rather than minutes into a run.
 *
 * @property json the validated document.
 * @property showCount how many shows it carries, so the dialog can be specific about the size of
 *   what is about to happen.
 */
data class PendingRestore(
    val json: String,
    val showCount: Int,
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

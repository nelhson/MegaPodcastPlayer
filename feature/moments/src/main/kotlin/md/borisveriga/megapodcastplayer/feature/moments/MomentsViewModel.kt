package md.borisveriga.megapodcastplayer.feature.moments

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.backup.BackupFileStore
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.MomentsRepository
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode

/**
 * Everything the moments screen renders.
 *
 * @property moments every saved moment, newest first.
 * @property isLoading true until the first database read arrives, so an empty list is not shown as
 *   "nothing saved" before anything has been read.
 * @property editing the moment whose note is open for editing, or null.
 * @property message a one-off outcome for the snackbar; cleared via
 *   [MomentsViewModel.onMessageShown].
 */
data class MomentsUiState(
    val moments: List<MomentWithEpisode> = emptyList(),
    val isLoading: Boolean = true,
    val editing: MomentWithEpisode? = null,
    val message: MomentsMessage? = null,
) {
    /** True when there is nothing saved and nothing left to wait for. */
    val isEmpty: Boolean get() = !isLoading && moments.isEmpty()
}

/**
 * A one-off outcome to show the user.
 *
 * State rather than an event channel, for the same reason the player's is: it survives a fold or a
 * rotation, and it leaves the UI state something a test can compare.
 */
sealed interface MomentsMessage {

    /**
     * A moment was deleted, and can be put back.
     *
     * @property restorable everything needed to write it again, since a deleted row is gone.
     */
    data class Deleted(val restorable: MomentWithEpisode) : MomentsMessage

    /** The export was written to the document the user chose. */
    data object Exported : MomentsMessage

    /** The document could not be written — a revoked grant, or a provider that went away. */
    data object ExportFailed : MomentsMessage

    /** There was nothing to export, so no document was written. */
    data object NothingToExport : MomentsMessage
}

/**
 * Drives the moments screen.
 *
 * The list comes straight from the database and every action writes back to it, so there is no
 * local copy that could drift; the only state held here is what is in flight — an open note editor,
 * a message waiting for its snackbar.
 *
 * @property momentsRepository the moments themselves, and the document they export to.
 * @property episodePlayer plays an episode from the second a moment names.
 * @property fileStore writes the export to the document the user picked. Shared with the backup
 *   because a Storage Access Framework write is the same job whatever is being written.
 * @property clock names the exported file after the day it was written.
 */
@HiltViewModel
class MomentsViewModel @Inject constructor(
    private val momentsRepository: MomentsRepository,
    private val episodePlayer: EpisodePlayer,
    private val fileStore: BackupFileStore,
    private val clock: Clock,
) : ViewModel() {

    private val messageState = MutableStateFlow<MomentsMessage?>(null)

    private val editingState = MutableStateFlow<MomentWithEpisode?>(null)

    val uiState: StateFlow<MomentsUiState> = combine(
        momentsRepository.observeMoments(),
        messageState,
        editingState,
    ) { moments, message, editing ->
        MomentsUiState(
            moments = moments,
            isLoading = false,
            // Re-read from the list rather than kept as the copy that opened the dialog, so an
            // edit saved elsewhere — from the player's own note dialog — is not overwritten by a
            // stale one this screen was still holding.
            editing = editing?.let { open ->
                moments.firstOrNull { it.moment.id == open.moment.id }
            },
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MomentsUiState(),
    )

    /**
     * Plays the episode a moment is in, a few seconds before the mark.
     *
     * The pre-roll is the moment's own, not this screen's: a moment is saved after the thing worth
     * remembering was said, so starting exactly on it starts after it. See
     * [md.borisveriga.megapodcastplayer.core.model.Moment.resumePositionMs].
     *
     * @param moment the moment to play.
     */
    fun play(moment: MomentWithEpisode) {
        viewModelScope.launch {
            episodePlayer.playFrom(moment.moment.episodeId, moment.moment.resumePositionMs)
        }
    }

    /**
     * Opens a moment's note for editing.
     *
     * @param moment the moment to edit.
     */
    fun edit(moment: MomentWithEpisode) {
        editingState.value = moment
    }

    /** Closes the note editor without writing anything. */
    fun cancelEdit() {
        editingState.value = null
    }

    /**
     * Saves the note being edited.
     *
     * @param note what the user typed; blank clears the note.
     */
    fun saveNote(note: String) {
        val target = editingState.value ?: return
        editingState.value = null
        viewModelScope.launch { momentsRepository.setNote(target.moment.id, note) }
    }

    /**
     * Deletes a moment, and offers it back.
     *
     * The whole row is carried into the message rather than its id: the row is gone from the
     * database the instant this runs, and an undo that had only an id would have nothing to
     * rebuild from.
     *
     * @param moment the moment to delete.
     */
    fun delete(moment: MomentWithEpisode) {
        viewModelScope.launch {
            momentsRepository.delete(moment.moment.id)
            messageState.value = MomentsMessage.Deleted(moment)
        }
    }

    /**
     * Puts back the moment the last [delete] removed.
     *
     * Re-marked rather than re-inserted with its old row id, which SQLite has already given away:
     * what the user is asking for is a moment at that spot in that episode, and that is exactly
     * what `mark` writes. The note is carried across; the creation time is not, because the moment
     * genuinely was saved again just now.
     */
    fun undoDelete() {
        val deleted = (messageState.value as? MomentsMessage.Deleted)?.restorable ?: return
        messageState.value = null
        viewModelScope.launch {
            momentsRepository.mark(
                episodeId = deleted.moment.episodeId,
                positionMs = deleted.moment.positionMs,
                note = deleted.moment.note,
            )
        }
    }

    /**
     * The file name to suggest when the picker asks where to put the export.
     *
     * @return a name carrying today's date, so a folder of exports sorts chronologically.
     */
    fun suggestedFileName(): String =
        FILE_NAME_PREFIX +
            FILE_NAME_DATE.format(clock.instant().atZone(ZoneId.systemDefault())) +
            FILE_NAME_SUFFIX

    /**
     * Writes every moment to the document the user created.
     *
     * An empty library writes nothing at all rather than a document with a heading and no content:
     * a file the user went to the trouble of naming should have something in it.
     *
     * @param uri the document the picker returned.
     */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val markdown = momentsRepository.exportMarkdown()
            if (markdown.isEmpty()) {
                messageState.value = MomentsMessage.NothingToExport
                return@launch
            }
            val written = fileStore.write(uri, markdown)
            messageState.value = if (written.isSuccess) {
                MomentsMessage.Exported
            } else {
                MomentsMessage.ExportFailed
            }
        }
    }

    /** Clears [MomentsUiState.message] once its snackbar has been shown. */
    fun onMessageShown() {
        messageState.value = null
    }

    private companion object {
        /** Keeps the database subscription alive across a rotation or a fold. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** ISO order, hyphen-separated, so every document provider accepts the name verbatim. */
        val FILE_NAME_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** Prefix of the suggested file name. */
        const val FILE_NAME_PREFIX = "megapodcastplayer-moments-"

        /** Suffix of the suggested file name. */
        const val FILE_NAME_SUFFIX = ".md"
    }
}

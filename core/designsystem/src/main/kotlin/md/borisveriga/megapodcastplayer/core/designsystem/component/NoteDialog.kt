package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

/**
 * A dialog for typing a short piece of free text.
 *
 * Written once because two screens ask the same question of the same data: the player, right after
 * a moment is marked, and the moments list, when an existing note is edited. Every word is a
 * parameter rather than a string resource of its own — a design-system component that carried its
 * own copy would be one neither screen could word for its own context, which is exactly what this
 * module's `strings.xml` says not to do.
 *
 * The field is focused on arrival, because a dialog that only opens on request is one the user has
 * already decided to type into, and it is capitalised as a sentence: a note is a remark, not a
 * label. The text survives a rotation; the dialog's own existence is the caller's state.
 *
 * @param title the dialog's heading.
 * @param placeholder shown in the empty field.
 * @param confirmLabel the confirming button's label.
 * @param dismissLabel the dismissing button's label.
 * @param onSave called with what was typed; blank means the note should be cleared.
 * @param onDismiss called when the user backs out.
 * @param initialNote the text to start from, for editing an existing note.
 */
@Composable
fun NoteDialog(
    title: String,
    placeholder: String,
    confirmLabel: String,
    dismissLabel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    initialNote: String = "",
) {
    var note by rememberSaveable(initialNote) { mutableStateOf(initialNote) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text(text = placeholder) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }) { Text(text = confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = dismissLabel) }
        },
    )
}

@ThemePreviews
@FontScalePreviews
@Composable
internal fun NoteDialogPreview() {
    MegaPodcastPlayerTheme {
        NoteDialog(
            title = "Note on this moment",
            placeholder = "What was said?",
            confirmLabel = "Save",
            dismissLabel = "Cancel",
            onSave = {},
            onDismiss = {},
            initialNote = "The bit about coroutine cancellation",
        )
    }
}

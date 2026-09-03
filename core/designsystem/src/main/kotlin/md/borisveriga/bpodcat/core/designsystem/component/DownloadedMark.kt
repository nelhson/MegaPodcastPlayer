package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme

/**
 * The small glyph that marks something as being on the device already.
 *
 * A mark rather than a [DownloadButton]: this says what is true, it does not offer to change it.
 * The lists it appears in — the library and the queue — are read down at a glance to decide what to
 * play next, and "is this going to need a connection?" is part of that decision. Managing the files
 * themselves is the downloads screen's job, and putting a control here would invite it to be tapped
 * on the one screen that cannot act on it.
 *
 * Deliberately smaller than the icon buttons around it and drawn in the same green the download
 * button uses when it is complete, so the two read as the same fact seen twice rather than as two
 * different states.
 *
 * @param modifier layout modifier.
 * @param contentDescription what a screen reader says for it, or null where the row's own text
 *   already carries the fact — the library's "2 downloaded" being exactly that case, where a
 *   description would have TalkBack say it twice.
 */
@Composable
internal fun DownloadedMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = Icons.Rounded.DownloadDone,
        contentDescription = contentDescription,
        tint = BPodcatTheme.colors.downloaded,
        modifier = modifier.size(MARK_SIZE),
    )
}

/**
 * How big the mark is drawn.
 *
 * Sized against the text beside it rather than against the 24dp icons elsewhere: at 24dp it read as
 * a button someone had forgotten to make tappable.
 */
private val MARK_SIZE = 16.dp

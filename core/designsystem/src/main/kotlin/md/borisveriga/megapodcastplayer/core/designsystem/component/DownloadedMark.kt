package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme

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
        tint = MegaPodcastPlayerTheme.colors.downloaded,
        modifier = modifier.size(MARK_SIZE),
    )
}

/**
 * The mark with a number beside it: how many of a show's episodes are on the device.
 *
 * Replaces the words that used to carry the same fact in a counts line — "412 episodes · 2
 * downloaded" is a sentence to read, and the glyph with a 2 after it is a thing to see. A show's
 * line is scanned down a list rather than read, and the number is the only part of "2 downloaded"
 * that differs from one row to the next.
 *
 * Announced as the words it replaced, because a glyph and a bare 2 are not a sentence: without the
 * description TalkBack would say "Downloaded, 2", which reads as an ordinal. The whole group is one
 * label, so the number is never read twice.
 *
 * @param count how many episodes are stored; callers draw nothing at zero rather than a "0".
 * @param modifier layout modifier.
 */
@Composable
fun DownloadedCount(count: Int, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.designsystem_downloaded_count, count, count)

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xxs),
    ) {
        DownloadedMark()
        Text(
            text = count.toString(),
            style = MegaPodcastPlayerTheme.type.numeric,
            color = MegaPodcastPlayerTheme.colors.downloaded,
        )
    }
}

/**
 * How big the mark is drawn.
 *
 * Sized against the text beside it rather than against the 24dp icons elsewhere: at 24dp it read as
 * a button someone had forgotten to make tappable.
 */
private val MARK_SIZE = 16.dp

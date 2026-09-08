package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.Moment

/**
 * The moments saved in the episode playing, each one a place to jump back to.
 *
 * The player showed a *count* of them and nothing else. That number is load-bearing — it is the
 * only visible evidence that the mark button did anything — but a count is a strange thing to show
 * and then refuse to open: the marks themselves could only be read on the Moments tab, which is a
 * long way to go to hear a sentence again.
 *
 * Earliest first, because this list is read *against* the episode rather than as a diary: it is a
 * set of places in one recording, and an order that jumped about in it would be unreadable beside a
 * scrubber that does not.
 *
 * Read-only. Editing a note and deleting a moment live on the Moments screen, which is where a
 * moment is a thing you *keep*; here it is a place you go.
 *
 * @param moments the episode's moments, earliest first.
 * @param onJumpTo seeks to a moment's position and closes the sheet.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsSheet(
    moments: List<Moment>,
    onJumpTo: (Moment) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = pluralStringResource(
            R.plurals.player_moments_sheet_title,
            moments.size,
            moments.size,
        ),
        subtitle = stringResource(R.string.player_moments_sheet_description),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal)
                .padding(bottom = MegaPodcastPlayerTheme.spacing.xl),
        ) {
            moments.forEach { moment ->
                MomentRow(moment = moment, onClick = { onJumpTo(moment) })
            }
        }
    }
}

/**
 * One moment: when it was marked, and whatever was written on it.
 *
 * A moment with no note is the common case — the button exists to be pressed without looking — so
 * the row says so in words rather than leaving a blank line. The timecode is the row's real
 * content either way.
 *
 * @param moment the moment.
 * @param onClick jumps to it.
 * @param modifier layout modifier.
 */
@Composable
private fun MomentRow(
    moment: Moment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val note = moment.note?.takeIf { it.isNotBlank() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = MegaPodcastPlayerTheme.spacing.minTouchTarget)
            .padding(vertical = MegaPodcastPlayerTheme.spacing.sm)
            .semantics(mergeDescendants = true) { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
    ) {
        Text(
            text = formatPosition(moment.positionMs),
            style = MegaPodcastPlayerTheme.type.numeric,
            color = MaterialTheme.colorScheme.primary,
            // Fixed, so the notes line up rather than stepping in and out as the timecodes cross
            // an hour; the same column the chapter list reserves, for the same reason.
            modifier = Modifier.width(MomentTimeWidth),
        )
        Text(
            text = note ?: stringResource(R.string.player_moment_no_note),
            style = MaterialTheme.typography.bodyMedium,
            color = if (note == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontStyle = if (note == null) FontStyle.Italic else FontStyle.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Width reserved for a moment's timecode, sized for `1:23:45`. */
private val MomentTimeWidth: Dp = 64.dp

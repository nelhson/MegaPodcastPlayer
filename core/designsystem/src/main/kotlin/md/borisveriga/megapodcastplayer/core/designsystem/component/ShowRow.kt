package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.Motion
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.PodcastSource

/**
 * The canonical row for a *show*, as [EpisodeRow] is for an episode.
 *
 * The two are deliberately separate components rather than one with a flag. They look alike, but
 * what they say about themselves is not: an episode announces how far through it you are, and a
 * show has no such state — a row that told a TalkBack user a podcast was "not played" would be
 * saying something that is not true of a podcast at all.
 *
 * @param title the show's name.
 * @param modifier layout modifier.
 * @param author the publisher, shown beside the source badge.
 * @param metadata the detail line, e.g. `412 episodes · 37 unplayed`. Set in tabular figures, so
 *   counts line up down the list. It no longer carries the downloaded count; see [downloadedCount].
 * @param artworkUrl cover art; null renders the themed placeholder.
 * @param downloadedCount how many of the show's episodes are on the device; drawn as the mark and
 *   the number in front of [metadata], so the library can be read down for what will play without a
 *   connection. Zero draws nothing.
 * @param source draws the badge that marks where the show came from; null draws none.
 * @param stateDescription what TalkBack announces about the row beyond its text, e.g. "3 new
 *   episodes". Null when the row has no state worth naming.
 * @param isSelected whether this row is the one a detail pane beside the list is showing. Only ever
 *   true where such a pane exists: on a single-pane screen a tap leaves the list entirely, so
 *   nothing is "selected" and the wash would be marking a fact that is not being asked about. The
 *   announcement is Compose's own `selected`, which TalkBack already has a word for, rather than a
 *   fourth sentence bolted onto [stateDescription].
 * @param onClick invoked when the row is pressed; the row is not focusable when null.
 * @param enabled whether the row accepts taps. A disabled row is still read out — a search result
 *   that cannot be added has to explain itself, not disappear.
 * @param trailing actions pinned to the end of the row.
 */
@Composable
fun ShowRow(
    title: String,
    modifier: Modifier = Modifier,
    author: String? = null,
    metadata: String? = null,
    artworkUrl: String? = null,
    downloadedCount: Int = 0,
    source: PodcastSource? = null,
    stateDescription: String? = null,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    // Transparent rather than `surface` when unselected, because a show row paints no ground of its
    // own: the library draws it straight onto the page, and naming a colour here would make the row
    // disagree with whatever it is put on. Animated for the same reason [EpisodeRow]'s now-playing
    // wash is — on a two-pane screen the selection moves under the finger, and a colour that
    // snapped would read as the list redrawing rather than as the same list answering.
    val selectionBackground by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = Motion.fade(),
        label = "showRowSelection",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(selectionBackground)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            // One node per row, for the same reason [EpisodeRow] merges: "Podlodka Podcast, Egor
            // Tolstoy, 412 episodes" is one thing to swipe past, not four.
            .semantics(mergeDescendants = true) {
                if (stateDescription != null) this.stateDescription = stateDescription
                // Set only when true. `selected = false` would have every row in every list — the
                // search results, the single-pane library — announce that it is not selected, which
                // is an answer to a question those screens never pose.
                if (isSelected) selected = true
            }
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(
                horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                vertical = MegaPodcastPlayerTheme.spacing.listItemVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
    ) {
        PodcastArtwork(url = artworkUrl, size = ArtworkSize.RowLarge)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xxs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (source != null || author != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
                ) {
                    // Badge first: a long publisher name that truncates can then never push it off
                    // the row, which is exactly when knowing the source matters most.
                    if (source != null) {
                        SourceBadge(source = source)
                    }
                    if (author != null) {
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (metadata != null || downloadedCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs),
                ) {
                    // Leading the counts rather than marking the title: on a show the fact is
                    // about how many of its episodes are stored, which is what this line says.
                    // It carries its own announcement now that the words are gone from [metadata].
                    if (downloadedCount > 0) {
                        DownloadedCount(count = downloadedCount)
                    }
                    if (metadata != null) {
                        Text(
                            text = metadata,
                            style = MegaPodcastPlayerTheme.type.numeric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (trailing != null) {
            trailing()
        }
    }
}

private val ROW_MIN_HEIGHT = 80.dp

@ThemePreviews
@Composable
internal fun ShowRowPreview() {
    MegaPodcastPlayerTheme {
        ShowRow(
            title = "Podlodka Podcast",
            author = "Egor Tolstoy",
            metadata = "412 episodes · 37 unplayed",
            downloadedCount = 2,
            source = PodcastSource.RSS,
            stateDescription = "3 new episodes",
            onClick = {},
        )
    }
}

/** The row as the list pane of a two-pane layout draws the show the detail pane is showing. */
@ThemePreviews
@Composable
internal fun ShowRowSelectedPreview() {
    MegaPodcastPlayerTheme {
        ShowRow(
            title = "Podlodka Podcast",
            author = "Egor Tolstoy",
            metadata = "412 episodes · 37 unplayed",
            downloadedCount = 2,
            source = PodcastSource.RSS,
            stateDescription = "3 new episodes",
            isSelected = true,
            onClick = {},
        )
    }
}

@ThemePreviews
@FontScalePreviews
@Composable
internal fun ShowRowYouTubePreview() {
    MegaPodcastPlayerTheme {
        ShowRow(
            title = "A very long playlist name that will not fit on one line at all",
            author = "Some Channel",
            metadata = "120 videos",
            source = PodcastSource.YOUTUBE,
            onClick = {},
        )
    }
}

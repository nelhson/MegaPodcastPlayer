package md.borisveriga.bpodcat.core.designsystem.component

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews
import md.borisveriga.bpodcat.core.model.PodcastSource

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
 * @param metadata the detail line, e.g. `412 episodes · 2 downloaded`. Set in tabular figures, so
 *   counts line up down the list.
 * @param artworkUrl cover art; null renders the themed placeholder.
 * @param isDownloaded whether any episode of the show is on the device; marks the row so the
 *   library can be read down for what will play without a connection.
 * @param source draws the badge that marks where the show came from; null draws none.
 * @param stateDescription what TalkBack announces about the row beyond its text, e.g. "3 new
 *   episodes". Null when the row has no state worth naming.
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
    isDownloaded: Boolean = false,
    source: PodcastSource? = null,
    stateDescription: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
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
            }
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(
                horizontal = BPodcatTheme.spacing.screenHorizontal,
                vertical = BPodcatTheme.spacing.listItemVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.md),
    ) {
        PodcastArtwork(url = artworkUrl, size = ArtworkSize.RowLarge)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.xxs),
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
                    horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
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
            if (metadata != null || isDownloaded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.xs),
                ) {
                    // Leading the counts rather than marking the title: on a show the fact is
                    // about how many of its episodes are stored, which is what this line says.
                    // No description — the line it sits on already spells out "2 downloaded",
                    // and a second reading of the same fact is noise.
                    if (isDownloaded) {
                        DownloadedMark()
                    }
                    if (metadata != null) {
                        Text(
                            text = metadata,
                            style = BPodcatTheme.type.numeric,
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
private fun ShowRowPreview() {
    BPodcatTheme {
        ShowRow(
            title = "Podlodka Podcast",
            author = "Egor Tolstoy",
            metadata = "412 episodes · 2 downloaded",
            source = PodcastSource.RSS,
            stateDescription = "3 new episodes",
            onClick = {},
        )
    }
}

@Preview
@Composable
private fun ShowRowYouTubePreview() {
    BPodcatTheme {
        ShowRow(
            title = "A very long playlist name that will not fit on one line at all",
            author = "Some Channel",
            metadata = "120 videos",
            source = PodcastSource.YOUTUBE,
            onClick = {},
        )
    }
}

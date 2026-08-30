package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews
import md.borisveriga.bpodcat.core.model.PodcastSource

/**
 * A show as a cover tile, for the library's grid.
 *
 * The artwork is the whole point of this layout, so it is sized by the grid rather than by an
 * [ArtworkSize] rung: the tile fills its cell and the square follows. The title stays under the
 * cover rather than over it — publishers put their own titles on their artwork often enough that
 * text on top would collide with it.
 *
 * @param title the show's name.
 * @param modifier layout modifier; the grid supplies the width.
 * @param artworkUrl cover art; null renders the themed placeholder.
 * @param author the publisher, one line under the title.
 * @param source draws the badge that marks where the show came from; null draws none.
 * @param badgeCount unplayed episodes; a count over the artwork's corner, hidden when zero.
 * @param stateDescription what TalkBack announces beyond the tile's text, e.g. "3 new episodes";
 *   the badge itself is decorative, because a bare number read out means nothing.
 * @param onClick invoked when the tile is pressed.
 */
@Composable
fun ShowTile(
    title: String,
    modifier: Modifier = Modifier,
    artworkUrl: String? = null,
    author: String? = null,
    source: PodcastSource? = null,
    badgeCount: Int = 0,
    stateDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .padding(BPodcatTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            PodcastArtwork(
                url = artworkUrl,
                shape = BPodcatTheme.shapes.artworkLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            if (badgeCount > 0) {
                Badge(
                    // Material's badge defaults to the error colour, which is a promise this badge
                    // is not making: unplayed episodes are the good news, not a fault.
                    containerColor = BPodcatTheme.colors.unplayed,
                    contentColor = BPodcatTheme.colors.onUnplayed,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(BPodcatTheme.spacing.sm)
                        // The row already says "3 new episodes"; the badge repeating "3" on its own
                        // would be a second, less useful announcement of the same fact.
                        .clearAndSetSemantics {},
                ) {
                    Text(text = badgeCount.toString())
                }
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (source != null || author != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.xs),
            ) {
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
    }
}

@ThemePreviews
@Composable
private fun ShowTilePreview() {
    BPodcatTheme {
        ShowTile(
            title = "Podlodka Podcast",
            author = "Egor Tolstoy",
            source = PodcastSource.RSS,
            badgeCount = 3,
            stateDescription = "3 new episodes",
            onClick = {},
        )
    }
}

@Preview
@Composable
private fun ShowTileNoBadgePreview() {
    BPodcatTheme {
        ShowTile(title = "Acquired", author = "Ben Gilbert and David Rosenthal", onClick = {})
    }
}

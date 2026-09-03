package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.PodcastSource

/**
 * Marks a show that did not come from an RSS feed.
 *
 * Renders nothing at all for [PodcastSource.RSS]. An ordinary podcast needs no explanation, and
 * badging every row would make the one badge that carries information invisible.
 *
 * The colour split is deliberate. The container tracks the Material scheme, so the badge sits
 * correctly in both themes; only the glyph carries a fixed red, because a fully citron badge would
 * not read as "YouTube" at a glance. The two reds live in [MegaPodcastPlayerTheme.colors] rather than as
 * literals here — a brand colour we do not get to choose is still a token.
 *
 * Not built on `Badge`, which already means "new episode count" one row over — two different things
 * must not look the same — nor on `AssistChip`, whose 32 dp minimum height does not fit a 64 dp row.
 *
 * @param source the show's origin.
 * @param modifier layout modifier.
 */
@Composable
fun SourceBadge(
    source: PodcastSource,
    modifier: Modifier = Modifier,
) {
    if (source == PodcastSource.RSS) return

    val badgeDescription = stringResource(R.string.designsystem_source_youtube_description)

    Row(
        modifier = modifier
            .clip(MegaPodcastPlayerTheme.shapes.pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = MegaPodcastPlayerTheme.spacing.sm, vertical = MegaPodcastPlayerTheme.spacing.xxs)
            // One label for TalkBack, rather than a glyph and a word announced separately.
            .clearAndSetSemantics {
                contentDescription = badgeDescription
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Rounded.SmartDisplay,
            contentDescription = null,
            tint = MegaPodcastPlayerTheme.colors.youtube,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.designsystem_youtube),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@ThemePreviews
@Composable
private fun SourceBadgePreview() {
    MegaPodcastPlayerTheme {
        SourceBadge(source = PodcastSource.YOUTUBE)
    }
}

package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.PodcastSource

/**
 * Marks a show that did not come from an RSS feed.
 *
 * Renders nothing at all for [PodcastSource.RSS]. An ordinary podcast needs no explanation, and
 * badging every row would make the one badge that carries information invisible.
 *
 * The colour split is deliberate. The container tracks the Material scheme, so the badge stays
 * legible in light, in dark and under dynamic colour; only the glyph carries a fixed red, chosen per
 * luminance to hold contrast against `surfaceContainerHighest` in both schemes. A fully dynamic
 * badge would not read as "YouTube" at a glance, and a dynamic *fill* would be unreadable on a
 * red-tinted wallpaper.
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

    val glyphColor = if (isSystemInDarkTheme()) YOUTUBE_RED_DARK else YOUTUBE_RED_LIGHT

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            // One label for TalkBack, rather than a glyph and a word announced separately.
            .clearAndSetSemantics { contentDescription = "From YouTube" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SmartDisplay,
            contentDescription = null,
            tint = glyphColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "YouTube",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Brand red darkened enough to hold contrast on a light container. */
private val YOUTUBE_RED_LIGHT = Color(0xFFC62828)

/** Brand red lightened enough to hold contrast on a dark container. */
private val YOUTUBE_RED_DARK = Color(0xFFFF6E6E)

@Preview(name = "YouTube badge, light")
@Composable
private fun SourceBadgeLightPreview() {
    // dynamicColor is off so the preview shows the app's own palette rather than the IDE's wallpaper.
    BPodcatTheme(darkTheme = false, dynamicColor = false) {
        SourceBadge(source = PodcastSource.YOUTUBE)
    }
}

@Preview(name = "YouTube badge, dark")
@Composable
private fun SourceBadgeDarkPreview() {
    BPodcatTheme(darkTheme = true, dynamicColor = false) {
        SourceBadge(source = PodcastSource.YOUTUBE)
    }
}

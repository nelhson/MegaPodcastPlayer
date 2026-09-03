package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

/**
 * A heading that divides a screen into sections.
 *
 * Set in the display face, because this is one of the few places small-scale UI gets to show the
 * brand's typography. Marked with `heading()` semantics so TalkBack users can jump between
 * sections instead of swiping through every row — the previous private copies in the settings
 * screen were plain `Text` and offered no such thing.
 *
 * @param text the heading.
 * @param modifier layout modifier.
 * @param background the ground to draw behind it. Opaque by default so the header stays legible
 *   when used as a sticky header over a scrolling list.
 * @param trailing an optional action aligned to the end, e.g. "See all".
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(
                start = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                end = MegaPodcastPlayerTheme.spacing.sm,
                top = MegaPodcastPlayerTheme.spacing.xl,
                bottom = MegaPodcastPlayerTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@ThemePreviews
@Composable
private fun SectionHeaderPreview() {
    MegaPodcastPlayerTheme {
        SectionHeader(text = "Today")
    }
}

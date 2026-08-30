package md.borisveriga.bpodcat.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkSize
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.media.PlaybackState

/**
 * The player at rest: a bar above the navigation bar showing what is playing.
 *
 * Draws no artwork of its own. The artwork belongs to [PlayerSheet], which keeps a single copy and
 * moves it between here and the expanded player as the sheet opens — a second copy fading in and
 * out underneath would give the effect away. What is left here is a gap of exactly the right size
 * for it to sit in.
 *
 * @param playback what the player is doing.
 * @param onPlayPause play/pause handler.
 * @param onSkipForward skip-ahead handler.
 * @param modifier layout modifier.
 */
@Composable
fun CollapsedPlayer(
    playback: PlaybackState,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // A thin progress line rather than a scrubber: the bar is a status indicator, and precise
        // seeking belongs on the expanded player where there is room to aim.
        LinearProgressIndicator(
            progress = { playback.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(collapsedProgressHeight)
                .clearAndSetSemantics { },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = collapsedHorizontalPadding,
                    vertical = collapsedVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.md),
        ) {
            // The hole the shared artwork is drawn into.
            Spacer(modifier = Modifier.size(ArtworkSize.Mini.dimension))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playback.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = playback.showTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (playback.isPlaying) {
                        Icons.Rounded.Pause
                    } else {
                        Icons.Rounded.PlayArrow
                    },
                    contentDescription = stringResource(
                        if (playback.isPlaying) R.string.player_pause else R.string.player_play,
                    ),
                )
            }

            IconButton(onClick = onSkipForward) {
                Icon(
                    imageVector = Icons.Rounded.Forward30,
                    contentDescription = stringResource(R.string.player_mini_skip_ahead),
                )
            }
        }
    }
}

/** Height of the whole collapsed bar; the sheet's resting height, and the space it reserves. */
val collapsedPlayerHeight: Dp = 64.dp

/** Height of the hairline progress line at the top of the bar. */
internal val collapsedProgressHeight: Dp = 4.dp

internal val collapsedHorizontalPadding: Dp = 12.dp
internal val collapsedVerticalPadding: Dp = 8.dp

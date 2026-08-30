package md.borisveriga.bpodcat.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.media.PlaybackState

/**
 * The persistent bar above the navigation bar showing what is playing.
 *
 * Renders nothing at all when the player is idle, so a user who has not started anything never sees
 * an empty bar taking up screen space.
 *
 * @param onExpand invoked when the bar is tapped, to open the full player.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt; shared with [NowPlayingScreen] by construction, since both
 *   read the same service.
 */
@Composable
fun MiniPlayerRoute(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MiniPlayer(
        playback = uiState.playback,
        onExpand = onExpand,
        onPlayPause = viewModel::togglePlayPause,
        onSkipForward = viewModel::skipForward,
        modifier = modifier,
    )
}

/**
 * Stateless mini player bar.
 *
 * @param playback what the player is doing; an idle state renders nothing.
 * @param onExpand tap handler for the bar itself.
 * @param onPlayPause play/pause handler.
 * @param onSkipForward skip-ahead handler.
 * @param modifier layout modifier.
 */
@Composable
fun MiniPlayer(
    playback: PlaybackState,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playback.isIdle) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column {
            // A thin progress line rather than a scrubber: the bar is a status indicator, and
            // precise seeking belongs on the full player where there is room to aim.
            LinearProgressIndicator(
                progress = { playback.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            )

            Row(
                modifier = Modifier
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PodcastArtwork(
                    url = playback.artworkUrl,
                    cornerRadius = 8,
                    modifier = Modifier.size(44.dp),
                )

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
}

@Preview
@Composable
private fun MiniPlayerPreview() {
    BPodcatTheme {
        MiniPlayer(
            playback = PlaybackState(
                episodeId = "e1",
                title = "Podlodka #400 – Мультиплатформа",
                showTitle = "Podlodka Podcast",
                isPlaying = true,
                positionMs = 1_200_000L,
                durationMs = 5_025_000L,
            ),
            onExpand = {},
            onPlayPause = {},
            onSkipForward = {},
        )
    }
}

package md.borisveriga.bpodcat.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import md.borisveriga.bpodcat.core.common.format.formatSpeed
import md.borisveriga.bpodcat.core.wearprotocol.QueuedEpisode
import md.borisveriga.bpodcat.wear.R
import md.borisveriga.bpodcat.wear.data.PhoneLink

/**
 * The watch's remote control, wired to its view model.
 *
 * @param viewModel supplies the phone's state and turns taps into commands.
 */
@Composable
fun WatchPlayerScreen(viewModel: WatchPlayerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WatchPlayerScreen(
        uiState = uiState,
        onTogglePlayPause = viewModel::togglePlayPause,
        onSkipForward = viewModel::skipForward,
        onSkipBack = viewModel::skipBack,
        onSkipToNext = viewModel::skipToNext,
        onSkipToPrevious = viewModel::skipToPrevious,
        onCycleSpeed = viewModel::cycleSpeed,
        onPlayQueued = viewModel::playQueued,
        onRetry = viewModel::retry,
    )
}

/**
 * The watch's remote control.
 *
 * Stateless so it can be previewed and screenshot-tested without a phone at the other end.
 *
 * Everything lives in one scrolling list rather than behind navigation: a watch screen fits about
 * four things at a time, and swiping down to the queue is one gesture where a nav graph would be
 * two plus a back stack to get wrong.
 *
 * @param uiState what to draw.
 * @param onTogglePlayPause invoked by the centre transport button.
 * @param onSkipForward invoked by the skip-ahead button.
 * @param onSkipBack invoked by the skip-back button.
 * @param onSkipToNext invoked by the next-episode button.
 * @param onSkipToPrevious invoked by the previous-episode button.
 * @param onCycleSpeed invoked by the speed button.
 * @param onPlayQueued invoked with the episode id when a queue row is tapped.
 * @param onRetry invoked when the user retries a failed connection.
 */
@Composable
fun WatchPlayerScreen(
    uiState: WatchPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onCycleSpeed: () -> Unit,
    onPlayQueued: (String) -> Unit,
    onRetry: () -> Unit,
) {
    // A phone we cannot reach makes every control below meaningless, so it replaces the screen
    // rather than sitting on top of it as a banner the user would tap straight through.
    if (uiState.link != PhoneLink.CONNECTED) {
        LinkProblemScreen(link = uiState.link, onRetry = onRetry)
        return
    }

    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (uiState.showsControls) {
                item { NowPlayingHeader(uiState) }
                item { ProgressRow(uiState) }
                item {
                    TransportRow(
                        uiState = uiState,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipForward = onSkipForward,
                        onSkipBack = onSkipBack,
                    )
                }
                item {
                    SecondaryRow(
                        uiState = uiState,
                        onSkipToPrevious = onSkipToPrevious,
                        onSkipToNext = onSkipToNext,
                        onCycleSpeed = onCycleSpeed,
                    )
                }
            } else {
                item { NothingPlaying(hasQueue = uiState.snapshot.upNext.isNotEmpty()) }
            }

            if (uiState.lastCommandFailed) {
                item { CommandFailedNote() }
            }

            if (uiState.snapshot.upNext.isNotEmpty()) {
                item { ListHeader { Text(text = stringResource(R.string.watch_up_next)) } }
                items(uiState.snapshot.upNext) { episode ->
                    QueueRow(episode = episode, onClick = { onPlayQueued(episode.id) })
                }
            }
        }
    }
}

/** Title, show and buffering state for whatever the phone has loaded. */
@Composable
private fun NowPlayingHeader(uiState: WatchPlayerUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = uiState.snapshot.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        if (uiState.snapshot.showTitle.isNotBlank()) {
            Text(
                text = uiState.snapshot.showTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/**
 * The scrubber and the two times around it.
 *
 * A linear indicator rather than the round one: this list scrolls, and a progress ring pinned to the
 * bezel would keep sliding away from the episode it describes.
 */
@Composable
private fun ProgressRow(uiState: WatchPlayerUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        LinearProgressIndicator(
            progress = { uiState.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPlaybackTime(uiState.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // Nothing is shown rather than "0:00" while the phone has not read the duration:
                // a zero-length episode is a claim, an empty label is just an absence.
                text = uiState.snapshot.knownDurationMs?.let(::formatPlaybackTime).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Skip back, play/pause, skip forward — the three buttons that get used while walking. */
@Composable
private fun TransportRow(
    uiState: WatchPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onSkipBack) {
            Icon(
                imageVector = skipBackIcon(uiState.snapshot.skipBackMs),
                contentDescription = skipContentDescription(uiState.snapshot.skipBackMs, forward = false),
            )
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(PLAY_BUTTON_SIZE),
        ) {
            Icon(
                imageVector = if (uiState.snapshot.isPlaying) {
                    Icons.Rounded.Pause
                } else {
                    Icons.Rounded.PlayArrow
                },
                contentDescription = stringResource(
                    if (uiState.snapshot.isPlaying) R.string.watch_pause else R.string.watch_play,
                ),
            )
        }

        FilledTonalIconButton(onClick = onSkipForward) {
            Icon(
                imageVector = skipForwardIcon(uiState.snapshot.skipForwardMs),
                contentDescription = skipContentDescription(uiState.snapshot.skipForwardMs, forward = true),
            )
        }
    }
}

/** Previous episode, speed, next episode — the controls used while sitting down. */
@Composable
private fun SecondaryRow(
    uiState: WatchPlayerUiState,
    onSkipToPrevious: () -> Unit,
    onSkipToNext: () -> Unit,
    onCycleSpeed: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipToPrevious) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.watch_previous_episode),
            )
        }

        TextButton(onClick = onCycleSpeed) {
            Text(text = formatSpeed(uiState.snapshot.speed))
        }

        IconButton(onClick = onSkipToNext, enabled = uiState.snapshot.hasNext) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.watch_next_episode),
            )
        }
    }
}

/** One "up next" row; tapping it asks the phone to play that episode. */
@Composable
private fun QueueRow(episode: QueuedEpisode, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = episode.title, maxLines = 2) },
        secondaryLabel = { Text(text = episode.showTitle, maxLines = 1) },
    )
}

/** Shown when the phone is reachable but has nothing loaded. */
@Composable
private fun NothingPlaying(hasQueue: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.watch_nothing_playing_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                if (hasQueue) {
                    R.string.watch_nothing_playing_with_queue
                } else {
                    R.string.watch_nothing_playing_empty
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shown after a command that could not be delivered. */
@Composable
private fun CommandFailedNote() {
    Text(
        text = stringResource(R.string.watch_command_failed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * The whole screen when there is no phone to control.
 *
 * @param link which flavour of unreachable; each gets the sentence that names what to do about it.
 * @param onRetry invoked by the retry button.
 */
@Composable
private fun LinkProblemScreen(link: PhoneLink, onRetry: () -> Unit) {
    ScreenScaffold {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(
                        when (link) {
                            PhoneLink.CHECKING -> R.string.watch_link_checking_title
                            PhoneLink.APP_NOT_INSTALLED -> R.string.watch_link_app_missing_title
                            else -> R.string.watch_link_disconnected_title
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(
                        when (link) {
                            PhoneLink.CHECKING -> R.string.watch_link_checking_description

                            PhoneLink.APP_NOT_INSTALLED ->
                                R.string.watch_link_app_missing_description

                            else -> R.string.watch_link_disconnected_description
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (link != PhoneLink.CHECKING) {
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.watch_retry))
                    }
                }
            }
        }
    }
}

/**
 * Picks the skip-ahead glyph matching the interval configured on the phone.
 *
 * Material only ships numbered icons for 5, 10 and 30 seconds. Showing "30" on a button that jumps
 * 45 is a small lie the user notices the first time they press it, so anything else falls back to
 * the unnumbered glyph. Deliberately the same rule as the phone's player, so the two agree.
 *
 * @param skipMs the configured distance.
 */
private fun skipForwardIcon(skipMs: Long): ImageVector = when (skipMs) {
    5_000L -> Icons.Rounded.Forward5
    10_000L -> Icons.Rounded.Forward10
    30_000L -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

/**
 * Picks the skip-back glyph matching the interval configured on the phone; see [skipForwardIcon].
 *
 * @param skipMs the configured distance.
 */
private fun skipBackIcon(skipMs: Long): ImageVector = when (skipMs) {
    5_000L -> Icons.Rounded.Replay5
    10_000L -> Icons.Rounded.Replay10
    30_000L -> Icons.Rounded.Replay30
    else -> Icons.Rounded.FastRewind
}

/**
 * Describes a skip button for TalkBack.
 *
 * The glyph carries the number visually; the description has to say it out loud.
 *
 * @param skipMs the configured distance.
 * @param forward true for the skip-ahead button.
 * @return the spoken label, pluralised on the number of seconds.
 */
@Composable
private fun skipContentDescription(skipMs: Long, forward: Boolean): String {
    val seconds = (skipMs / 1_000L).coerceAtLeast(1L).toInt()
    return pluralStringResource(
        id = if (forward) R.plurals.watch_skip_forward else R.plurals.watch_skip_back,
        count = seconds,
        seconds,
    )
}

/** The play button is deliberately larger than its neighbours: it is the one pressed blind. */
private val PLAY_BUTTON_SIZE = 60.dp

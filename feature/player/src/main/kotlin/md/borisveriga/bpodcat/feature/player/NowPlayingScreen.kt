package md.borisveriga.bpodcat.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.util.Locale
import md.borisveriga.bpodcat.core.common.format.formatPosition
import md.borisveriga.bpodcat.core.designsystem.component.MessageState
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.media.PlayableEpisode
import md.borisveriga.bpodcat.core.media.PlaybackState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.PlaybackSettings

/**
 * The full-screen player.
 *
 * @param onCollapse invoked when the user dismisses the screen.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun NowPlayingRoute(
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NowPlayingScreen(
        uiState = uiState,
        onCollapse = onCollapse,
        onPlayPause = viewModel::togglePlayPause,
        onSeek = viewModel::seekTo,
        onSkipForward = viewModel::skipForward,
        onSkipBack = viewModel::skipBack,
        onSkipToNext = viewModel::skipToNext,
        onSkipToPrevious = viewModel::skipToPrevious,
        onCycleSpeed = viewModel::cycleSpeed,
        onQueuedEpisodeClick = viewModel::playQueued,
        onRemoveFromQueue = viewModel::removeFromQueue,
        onErrorShown = viewModel::onErrorShown,
        modifier = modifier,
    )
}

/**
 * Stateless now-playing screen: artwork, transport controls and the queue.
 *
 * @param uiState what to render.
 * @param onCollapse dismiss handler.
 * @param onPlayPause play/pause handler.
 * @param onSeek absolute-seek handler, called once when the user releases the scrubber.
 * @param onSkipForward skip-ahead handler.
 * @param onSkipBack skip-back handler.
 * @param onSkipToNext next-episode handler.
 * @param onSkipToPrevious previous-episode handler.
 * @param onCycleSpeed speed-button handler.
 * @param onQueuedEpisodeClick plays a queued episode.
 * @param onRemoveFromQueue removes a queued episode.
 * @param onErrorShown called once a playback error has been surfaced.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    uiState: PlayerUiState,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onCycleSpeed: () -> Unit,
    onQueuedEpisodeClick: (String) -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.playback.errorMessage) {
        val error = uiState.playback.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar("Playback problem: $error")
        onErrorShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.playback.showTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Close the player",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.isIdle) {
            MessageState(
                icon = Icons.Rounded.PlayArrow,
                title = "Nothing playing",
                description = "Pick an episode from one of your shows and it will appear here.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                NowPlayingHeader(
                    playback = uiState.playback,
                    settings = uiState.settings,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onSkipForward = onSkipForward,
                    onSkipBack = onSkipBack,
                    onSkipToNext = onSkipToNext,
                    onSkipToPrevious = onSkipToPrevious,
                    onCycleSpeed = onCycleSpeed,
                )
            }

            val upNext = uiState.upNext
            if (upNext.isNotEmpty()) {
                item {
                    Text(
                        text = "Up next",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                items(items = upNext, key = { it.episode.id }) { queued ->
                    QueueRow(
                        entry = queued,
                        onClick = { onQueuedEpisodeClick(queued.episode.id) },
                        onRemove = { onRemoveFromQueue(queued.episode.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Artwork, titles, scrubber and transport controls.
 *
 * @param playback current playback state.
 * @param settings the user's speed and skip preferences.
 * @param onPlayPause play/pause handler.
 * @param onSeek absolute-seek handler.
 * @param onSkipForward skip-ahead handler.
 * @param onSkipBack skip-back handler.
 * @param onSkipToNext next-episode handler.
 * @param onSkipToPrevious previous-episode handler.
 * @param onCycleSpeed speed-button handler.
 * @param modifier layout modifier.
 */
@Composable
private fun NowPlayingHeader(
    playback: PlaybackState,
    settings: PlaybackSettings,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PodcastArtwork(
            url = playback.artworkUrl,
            cornerRadius = 16,
            modifier = Modifier
                .fillMaxWidth(fraction = 0.72f)
                .aspectRatio(1f)
                .padding(top = 8.dp),
        )

        Text(
            text = playback.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = playback.showTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        Scrubber(playback = playback, onSeek = onSeek)

        TransportControls(
            playback = playback,
            settings = settings,
            onPlayPause = onPlayPause,
            onSkipForward = onSkipForward,
            onSkipBack = onSkipBack,
            onSkipToNext = onSkipToNext,
            onSkipToPrevious = onSkipToPrevious,
        )

        TextButton(onClick = onCycleSpeed, modifier = Modifier.padding(top = 4.dp)) {
            Text(text = formatSpeed(playback.speed))
        }
    }
}

/**
 * The seek bar and its position labels.
 *
 * While the user drags, the thumb follows the finger rather than the player: the position only jumps
 * once, on release, instead of fighting the 500 ms state ticks on the way.
 *
 * @param playback current playback state.
 * @param onSeek called once, with the released position.
 * @param modifier layout modifier.
 */
@Composable
private fun Scrubber(
    playback: PlaybackState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = playback.knownDurationMs
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }

    // A duration the player has not read yet leaves nothing to scrub along.
    val enabled = durationMs != null
    val displayedMs = dragPositionMs ?: playback.positionMs

    Column(modifier = modifier.fillMaxWidth().padding(top = 16.dp)) {
        Slider(
            value = displayedMs.toFloat(),
            onValueChange = { value -> dragPositionMs = value.toLong() },
            onValueChangeFinished = {
                dragPositionMs?.let(onSeek)
                dragPositionMs = null
            },
            valueRange = 0f..(durationMs ?: 1L).toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = "Playback position"
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPosition(displayedMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // An em dash rather than "0:00" while the duration is unknown: a wrong number reads
                // as fact, a dash reads as "not yet".
                text = durationMs?.let(::formatPosition) ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Previous, skip back, play/pause, skip ahead, next.
 *
 * @param playback current playback state.
 * @param settings the user's skip intervals, which choose the button glyphs.
 * @param onPlayPause play/pause handler.
 * @param onSkipForward skip-ahead handler.
 * @param onSkipBack skip-back handler.
 * @param onSkipToNext next-episode handler.
 * @param onSkipToPrevious previous-episode handler.
 * @param modifier layout modifier.
 */
@Composable
private fun TransportControls(
    playback: PlaybackState,
    settings: PlaybackSettings,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipToPrevious) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "Restart or go to the previous episode",
            )
        }

        IconButton(onClick = onSkipBack) {
            Icon(
                imageVector = skipBackIcon(settings.skipBackMs),
                contentDescription = skipContentDescription(settings.skipBackMs, forward = false),
                modifier = Modifier.size(32.dp),
            )
        }

        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .size(64.dp),
        ) {
            if (playback.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        // The button already announces what tapping does; the spinner is decoration.
                        .clearAndSetSemantics { },
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = if (playback.isPlaying) {
                        Icons.Rounded.Pause
                    } else {
                        Icons.Rounded.PlayArrow
                    },
                    contentDescription = if (playback.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        IconButton(onClick = onSkipForward) {
            Icon(
                imageVector = skipForwardIcon(settings.skipForwardMs),
                contentDescription = skipContentDescription(settings.skipForwardMs, forward = true),
                modifier = Modifier.size(32.dp),
            )
        }

        IconButton(onClick = onSkipToNext, enabled = playback.hasNext) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Next episode",
            )
        }
    }
}

/**
 * One "up next" row.
 *
 * @param entry the queued episode.
 * @param onClick plays it immediately.
 * @param onRemove drops it from the queue.
 * @param modifier layout modifier.
 */
@Composable
private fun QueueRow(
    entry: PlayableEpisode,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PodcastArtwork(url = entry.artworkUrl, cornerRadius = 8, modifier = Modifier.size(40.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.episode.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.showTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove ${entry.episode.title} from the queue",
            )
        }
    }
}

/**
 * Formats a playback rate for the speed button.
 *
 * Whole rates lose their decimal — `2x`, not `2.0x` — because that is how every podcast app writes
 * it and how people say it.
 */
private fun formatSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100f) / 100f
    return if (rounded % 1f == 0f) {
        String.format(Locale.US, "%.0fx", rounded)
    } else {
        String.format(Locale.US, "%.2fx", rounded).trimEnd('0').trimEnd('.') + "x"
    }
}

@Preview
@Composable
private fun NowPlayingScreenPreview() {
    BPodcatTheme {
        NowPlayingScreen(
            uiState = PlayerUiState(
                playback = PlaybackState(
                    isConnected = true,
                    episodeId = "e1",
                    title = "Podlodka #400 – Мультиплатформа",
                    showTitle = "Podlodka Podcast",
                    isPlaying = true,
                    positionMs = 1_200_000L,
                    durationMs = 5_025_000L,
                    speed = 1.5f,
                    queueEpisodeIds = listOf("e1", "e2"),
                ),
                queue = listOf(
                    previewPlayable("e1", "Podlodka #400 – Мультиплатформа"),
                    previewPlayable("e2", "Podlodka #401 – Compose"),
                ),
            ),
            onCollapse = {},
            onPlayPause = {},
            onSeek = {},
            onSkipForward = {},
            onSkipBack = {},
            onSkipToNext = {},
            onSkipToPrevious = {},
            onCycleSpeed = {},
            onQueuedEpisodeClick = {},
            onRemoveFromQueue = {},
            onErrorShown = {},
        )
    }
}

/** Builds a queue entry for the preview. */
private fun previewPlayable(id: String, title: String) = PlayableEpisode(
    episode = Episode(
        id = id,
        podcastId = "p1",
        guid = "guid-$id",
        title = title,
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 5_025_000L,
        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
        sizeBytes = null,
    ),
    showTitle = "Podlodka Podcast",
    showArtworkUrl = null,
)

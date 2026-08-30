package md.borisveriga.bpodcat.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import md.borisveriga.bpodcat.core.common.format.formatPosition
import md.borisveriga.bpodcat.core.common.format.formatSpeed
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.media.PlaybackState
import md.borisveriga.bpodcat.core.model.PlaybackSettings

/**
 * The player fully open: titles, scrubber, transport controls and the way into the queue.
 *
 * Like [CollapsedPlayer] this draws no artwork — [PlayerSheet] owns the single copy that travels
 * between the two — but it does leave room for it, along with room for the sheet's own header. Both
 * gaps are at the top, so everything below flows normally and scrolls as one column at large font
 * scales.
 *
 * @param uiState what to render.
 * @param heroArtworkSize how tall the artwork will be, so the right amount of room is left for it.
 * @param onPlayPause play/pause handler.
 * @param onSeek absolute-seek handler, called once when the user releases the scrubber.
 * @param onSkipForward skip-ahead handler.
 * @param onSkipBack skip-back handler.
 * @param onSkipToNext next-episode handler.
 * @param onSkipToPrevious previous-episode handler.
 * @param onCycleSpeed speed-button handler.
 * @param onOpenQueue opens the queue screen.
 * @param modifier layout modifier.
 */
@Composable
fun ExpandedPlayer(
    uiState: PlayerUiState,
    heroArtworkSize: Dp,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback = uiState.playback

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = expandedHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The sheet's header and the shared artwork are drawn over this column, not in it.
        Spacer(
            modifier = Modifier.height(
                expandedHeaderHeight + expandedArtworkTopGap + heroArtworkSize,
            ),
        )

        Text(
            text = playback.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = BPodcatTheme.spacing.xl),
        )
        Text(
            text = playback.showTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = BPodcatTheme.spacing.xs),
        )

        Scrubber(playback = playback, onSeek = onSeek)

        TransportControls(
            playback = playback,
            settings = uiState.settings,
            onPlayPause = onPlayPause,
            onSkipForward = onSkipForward,
            onSkipBack = onSkipBack,
            onSkipToNext = onSkipToNext,
            onSkipToPrevious = onSkipToPrevious,
        )

        TextButton(onClick = onCycleSpeed, modifier = Modifier.padding(top = BPodcatTheme.spacing.xs)) {
            Text(text = formatSpeed(playback.speed))
        }

        val upNextCount = uiState.upNext.size
        if (upNextCount > 0) {
            UpNextLink(count = upNextCount, onClick = onOpenQueue)
        }

        Spacer(modifier = Modifier.height(BPodcatTheme.spacing.xl))
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
    // Built here rather than inside `semantics`, which is not a composable scope.
    val scrubberDescription = stringResource(R.string.player_scrubber)
    val displayedMs = dragPositionMs ?: playback.positionMs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = BPodcatTheme.spacing.lg),
    ) {
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
                contentDescription = scrubberDescription
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPosition(displayedMs),
                style = BPodcatTheme.type.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // An em dash rather than "0:00" while the duration is unknown: a wrong number reads
                // as fact, a dash reads as "not yet".
                text = durationMs?.let(::formatPosition)
                    ?: stringResource(R.string.player_unknown_duration),
                style = BPodcatTheme.type.numeric,
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
            .padding(top = BPodcatTheme.spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipToPrevious) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.player_previous),
            )
        }

        IconButton(onClick = onSkipBack) {
            Icon(
                imageVector = skipBackIcon(settings.skipBackMs),
                contentDescription = skipContentDescription(settings.skipBackMs, forward = false),
                modifier = Modifier.size(SkipGlyphSize),
            )
        }

        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .padding(horizontal = BPodcatTheme.spacing.md)
                .size(PlayButtonSize),
        ) {
            if (playback.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(BufferingGlyphSize)
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
                    contentDescription = stringResource(
                        if (playback.isPlaying) R.string.player_pause else R.string.player_play,
                    ),
                    modifier = Modifier.size(PlayGlyphSize),
                )
            }
        }

        IconButton(onClick = onSkipForward) {
            Icon(
                imageVector = skipForwardIcon(settings.skipForwardMs),
                contentDescription = skipContentDescription(settings.skipForwardMs, forward = true),
                modifier = Modifier.size(SkipGlyphSize),
            )
        }

        IconButton(onClick = onSkipToNext) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.player_next),
            )
        }
    }
}

/**
 * The way into the queue.
 *
 * The queue used to be listed here, under the transport controls, which meant it could only be
 * reached by scrolling past the artwork and could not be reordered at all. It is a list the user
 * manages, so it has its own screen now, and this is the door to it.
 *
 * @param count how many episodes follow the one playing.
 * @param onClick opens the queue.
 * @param modifier layout modifier.
 */
@Composable
private fun UpNextLink(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = BPodcatTheme.spacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = androidx.compose.ui.res.pluralStringResource(
                R.plurals.player_queue_summary,
                count,
                count,
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Height of the sheet's own header strip, which the body has to leave room for. */
internal val expandedHeaderHeight: Dp = 56.dp

/** Gap between the header and the top of the artwork. */
internal val expandedArtworkTopGap: Dp = 8.dp

/** Side padding for the expanded body. */
internal val expandedHorizontalPadding: Dp = 20.dp

/** Fraction of the sheet's width the artwork occupies when fully expanded. */
internal const val HERO_ARTWORK_WIDTH_FRACTION: Float = 0.72f

private val SkipGlyphSize: Dp = 32.dp
private val PlayButtonSize: Dp = 64.dp
private val PlayGlyphSize: Dp = 36.dp
private val BufferingGlyphSize: Dp = 28.dp

/** Two decimal places, as a divisor; the speed button never shows more than that. */

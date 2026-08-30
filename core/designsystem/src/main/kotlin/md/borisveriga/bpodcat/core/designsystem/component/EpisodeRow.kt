package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import md.borisveriga.bpodcat.core.designsystem.R
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.Motion
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews
import md.borisveriga.bpodcat.core.model.DownloadState

/**
 * The canonical episode row.
 *
 * Every list in the app shows the same thing — artwork, an episode title, the show it came from, a
 * metadata line, how far through it you are, and one or two actions — and before this component
 * existed that row was written five separate times, in the library, downloads, podcast detail,
 * search and player screens, drifting apart in typography (`titleSmall` in four of them,
 * `titleMedium` in the fifth) and padding (12, 16 and 20dp).
 *
 * Accessibility is handled once, here, which is what fixes three screens that had no `semantics`
 * usage at all: the row merges its children into a single focusable node with a [Role.Button], and
 * the played/downloaded/now-playing state is announced as a `stateDescription` rather than being
 * conveyed only by a tinted pixel.
 *
 * @param title the episode title.
 * @param modifier layout modifier.
 * @param showTitle the owning show, shown above the title when the list mixes shows.
 * @param metadata the trailing detail line, e.g. `42 min · 2 days ago`. Set in tabular figures.
 * @param artworkUrl artwork for the leading square; null renders the themed placeholder.
 * @param artworkSize which rung of [ArtworkSize] the leading square uses.
 * @param isUnplayed whether to mark the episode as new; draws the title heavier and adds a dot.
 * @param isPlayed whether the episode has been finished; dims the row.
 * @param playedFraction progress through the episode in `0f..1f`; drawn as a hairline under the row.
 * @param isNowPlaying whether this episode is the one loaded in the player.
 * @param isPlaying whether that episode is actually running, as opposed to loaded and paused.
 * @param isSelected whether the row is part of a multi-selection; tints the row and announces
 *   itself as selected.
 * @param onClick invoked when the row is pressed; the row is not focusable when null.
 * @param onLongClick invoked on a long press, usually to start selecting. Published to
 *   accessibility services as a named custom action, because a long press is a gesture a TalkBack
 *   user cannot make.
 * @param longClickLabel names that custom action, e.g. "Select". Required in practice whenever
 *   [onLongClick] is set.
 * @param trailing actions pinned to the end of the row, e.g. a [DownloadButton].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    title: String,
    modifier: Modifier = Modifier,
    showTitle: String? = null,
    metadata: String? = null,
    artworkUrl: String? = null,
    artworkSize: ArtworkSize = ArtworkSize.Row,
    isUnplayed: Boolean = false,
    isPlayed: Boolean = false,
    playedFraction: Float = 0f,
    isNowPlaying: Boolean = false,
    isPlaying: Boolean = false,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val background by animateColorAsState(
        targetValue = when {
            // Selection wins over now-playing: while a selection is being made, which rows are in
            // it is the only thing the user is looking for.
            isSelected -> MaterialTheme.colorScheme.secondaryContainer

            isNowPlaying -> BPodcatTheme.colors.nowPlayingContainer

            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = Motion.fade(),
        label = "rowBackground",
    )

    val state = when {
        isNowPlaying -> stringResource(R.string.designsystem_state_now_playing)

        isPlayed -> stringResource(R.string.designsystem_state_played)

        playedFraction > 0f -> stringResource(
            R.string.designsystem_state_in_progress,
            (playedFraction * PERCENT).roundToInt(),
        )

        else -> stringResource(R.string.designsystem_state_unplayed)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .rowGestures(
                onClick = onClick,
                onLongClick = onLongClick,
                longClickLabel = longClickLabel,
            )
            // One node per row: TalkBack should say "Hard Fork, The AI bubble, 42 minutes, 30
            // percent played", not walk four separate labels.
            .semantics(mergeDescendants = true) {
                stateDescription = state
                // Only when true: every row announcing "not selected" outside a selection would be
                // noise on a screen where nothing is selectable yet.
                if (isSelected) selected = true
            },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(
                    horizontal = BPodcatTheme.spacing.screenHorizontal,
                    vertical = BPodcatTheme.spacing.listItemVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.md),
        ) {
            PodcastArtwork(url = artworkUrl, size = artworkSize)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.xxs),
            ) {
                if (showTitle != null) {
                    Text(
                        text = showTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
                ) {
                    if (isUnplayed && !isNowPlaying) {
                        Box(
                            modifier = Modifier
                                .size(UNPLAYED_DOT)
                                .clip(BPodcatTheme.shapes.pill)
                                .background(BPodcatTheme.colors.unplayed),
                        )
                    }
                    if (isNowPlaying) {
                        NowPlayingBars(playing = isPlaying)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isUnplayed) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isPlayed) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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

            if (trailing != null) {
                trailing()
            }
        }

        // Progress is a hairline at the very bottom of the row rather than a bar inside it: it is
        // ambient information, and giving it its own line pushed every row 8dp taller for something
        // that is usually zero.
        if (playedFraction > 0f && !isPlayed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BPodcatTheme.spacing.screenHorizontal)
                    .height(PROGRESS_HEIGHT)
                    .clip(BPodcatTheme.shapes.pill)
                    .background(BPodcatTheme.colors.waveformTrack),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(playedFraction.coerceIn(0f, 1f))
                        .height(PROGRESS_HEIGHT)
                        .clip(BPodcatTheme.shapes.pill)
                        .background(BPodcatTheme.colors.waveform),
                )
            }
        }
    }
}

/**
 * The gestures a row accepts, which is none, a tap, or a tap and a long press.
 *
 * A modifier factory rather than three branches inside [EpisodeRow] so the row itself stays
 * readable, and so the rule that a long press is always published with a label lives in one place.
 *
 * @param onClick tap handler; null makes the row inert and unfocusable.
 * @param onLongClick long-press handler; ignored without [onClick], since a row that can only be
 *   long-pressed offers no way in for anyone who cannot make the gesture.
 * @param longClickLabel names the long press for accessibility services.
 * @return the modifier to apply.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.rowGestures(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    longClickLabel: String?,
): Modifier = when {
    onClick != null && onLongClick != null -> combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = longClickLabel,
        role = Role.Button,
    )

    onClick != null -> clickable(role = Role.Button, onClick = onClick)

    else -> this
}

private val ROW_MIN_HEIGHT = 72.dp
private val UNPLAYED_DOT = 8.dp
private val PROGRESS_HEIGHT = 3.dp
private const val PERCENT = 100f

@ThemePreviews
@Composable
private fun EpisodeRowPreview() {
    BPodcatTheme {
        Column {
            EpisodeRow(
                title = "The AI bubble, revisited",
                showTitle = "Hard Fork",
                metadata = "42 min · 2 days ago",
                isUnplayed = true,
                onClick = {},
                trailing = {
                    DownloadButton(
                        state = DownloadState.NOT_DOWNLOADED,
                        progressPercent = 0f,
                        onClick = {},
                    )
                },
            )
            EpisodeRow(
                title = "Nvidia, Part III: The Dan Ives Chronicles",
                showTitle = "Acquired",
                metadata = "3 h 12 min · 30% played",
                playedFraction = 0.3f,
                isNowPlaying = true,
                isPlaying = true,
                onClick = {},
            )
            EpisodeRow(
                title = "Why is it so hard to buy a mattress?",
                showTitle = "Search Engine",
                metadata = "58 min · last week",
                isPlayed = true,
                onClick = {},
                trailing = {
                    DownloadButton(
                        state = DownloadState.COMPLETED,
                        progressPercent = 100f,
                        onClick = {},
                    )
                },
            )
        }
    }
}

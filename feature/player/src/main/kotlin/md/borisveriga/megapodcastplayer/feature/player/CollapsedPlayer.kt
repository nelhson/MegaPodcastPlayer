package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseButton
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseSize
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

/**
 * The player at rest: a bar above the navigation bar showing what is playing.
 *
 * Draws no artwork of its own. The artwork belongs to [PlayerSheet], which keeps a single copy and
 * moves it between here and the expanded player as the sheet opens — a second copy fading in and
 * out underneath would give the effect away. What is left here is a gap of exactly the right size
 * for it to sit in.
 *
 * Three controls, not one. The bar used to carry play/pause and a fixed `Forward30` glyph, which
 * was wrong twice over: there was no way to replay a sentence without opening the sheet, and the
 * glyph said 30 whatever the user had configured, so it lied for four of the five settings. Both
 * skip buttons now take their number from [PlaybackSettings] the same way the expanded player's do.
 *
 * @param playback what the player is doing.
 * @param settings the user's skip intervals, which choose the button glyphs.
 * @param onPlayPause play/pause handler.
 * @param onSkipBack skip-back handler.
 * @param onSkipForward skip-ahead handler.
 * @param modifier layout modifier.
 */
@Composable
fun CollapsedPlayer(
    playback: PlaybackState,
    settings: PlaybackSettings,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
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

            IconButton(onClick = onSkipBack) {
                Icon(
                    imageVector = skipBackIcon(settings.skipBackMs),
                    contentDescription = skipContentDescription(settings.skipBackMs, forward = false),
                )
            }

            // The signature control rather than a stock `IconButton`: the same morph the expanded
            // player uses, one rung smaller, so the bar and the sheet are visibly the same player.
            PlayPauseButton(
                playing = playback.isPlaying,
                onToggle = { onPlayPause() },
                size = PlayPauseSize.Medium,
                buffering = playback.isBuffering,
            )

            IconButton(onClick = onSkipForward) {
                Icon(
                    imageVector = skipForwardIcon(settings.skipForwardMs),
                    contentDescription = skipContentDescription(settings.skipForwardMs, forward = true),
                )
            }
        }
    }
}

/**
 * Height of the whole collapsed bar; the sheet's resting height, and the space it reserves.
 *
 * Measured rather than fixed. It used to be a flat 64 dp holding two lines of text, which meant the
 * lines alone outgrew the bar somewhere around a 150 % font scale and the show's name was clipped
 * in half — on the one surface that is on screen for the whole session. The floor stays 64 dp, so
 * nothing moves at the default scale; above it the bar grows with the type it contains.
 *
 * A function rather than a constant because every reader is already a composable, and because the
 * sheet's geometry has to agree with it: [PlayerSheet] lerps its height from this value and
 * positions the travelling artwork against it, so a bar that measured itself independently would
 * leave the artwork hovering somewhere else.
 *
 * @return the bar's height at the current font scale.
 */
@Composable
fun collapsedPlayerHeight(): Dp {
    val titleLine = MaterialTheme.typography.titleSmall.lineHeight
    val showLine = MaterialTheme.typography.bodySmall.lineHeight
    // A style may leave its line height to the font; the app's do not, but the arithmetic has to
    // survive one that does rather than crashing on an unspecified `TextUnit`.
    val textHeight = with(LocalDensity.current) {
        val title = if (titleLine.isUnspecified) 0.dp else titleLine.toDp()
        val show = if (showLine.isUnspecified) 0.dp else showLine.toDp()
        title + show
    }
    val content = collapsedProgressHeight + collapsedVerticalPadding * 2 + textHeight
    return maxOf(CollapsedPlayerMinHeight, content)
}

/** The bar never shrinks below the touch-target-plus-padding it was designed at. */
private val CollapsedPlayerMinHeight: Dp = 64.dp

/** Height of the hairline progress line at the top of the bar. */
internal val collapsedProgressHeight: Dp = 4.dp

/** Side padding for the bar; the same token every other screen's rows use. */
internal val collapsedHorizontalPadding: Dp
    @Composable get() = MegaPodcastPlayerTheme.spacing.md

/** Padding above and below the bar's content. */
internal val collapsedVerticalPadding: Dp
    @Composable get() = MegaPodcastPlayerTheme.spacing.sm

/**
 * Sample state for the player previews, shared by the bar and the sheet.
 *
 * A real title and a real show name rather than "Lorem": the bar is one line of each, and a
 * placeholder short enough to fit is exactly the placeholder that hides the clipping this preview
 * exists to catch.
 */
internal val previewPlayback = PlaybackState(
    isConnected = true,
    episodeId = "e1",
    title = "Podlodka #492 — Как устроены дизайн-системы",
    showTitle = "Podlodka Podcast",
    isPlaying = true,
    positionMs = 724_000L,
    durationMs = 2_530_000L,
    queueEpisodeIds = listOf("e1"),
)

/**
 * A queue whose head is the episode [previewPlayback] is playing.
 *
 * The head matters: the queue screen lists what comes *after* the loaded episode, so a sample whose
 * first entry is not the one playing would preview a screen that cannot occur.
 */
internal val previewQueue: List<PlayableEpisode> = listOf(
    previewEpisode("e1", "Podlodka #492 — Как устроены дизайн-системы"),
    previewEpisode("e2", "Podlodka #493 — Тестирование на проде"),
    previewEpisode("e3", "Podlodka #494 — Найм джунов"),
).map { episode ->
    PlayableEpisode(
        episode = episode,
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )
}

/**
 * One sample episode.
 *
 * @param id the episode id, which is also the queue key.
 * @param title what the row shows.
 */
private fun previewEpisode(id: String, title: String) = Episode(
    id = id,
    podcastId = "podcast-1",
    guid = "guid-$id",
    title = title,
    description = "",
    audioUrl = "https://cdn.example.com/$id.mp3",
    artworkUrl = null,
    durationMs = 2_530_000L,
    publishedAt = Instant.parse("2026-09-01T06:00:00Z"),
    sizeBytes = null,
)

@ThemePreviews
@FontScalePreviews
@Composable
private fun CollapsedPlayerPreview() {
    MegaPodcastPlayerTheme {
        CollapsedPlayer(
            playback = previewPlayback,
            settings = PlaybackSettings(),
            onPlayPause = {},
            onSkipBack = {},
            onSkipForward = {},
            modifier = Modifier.height(collapsedPlayerHeight()),
        )
    }
}

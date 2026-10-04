package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtwork
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.VideoQuality

/**
 * The player at rest: a bar above the navigation bar showing what is playing.
 *
 * Draws no artwork of its own. The artwork belongs to [PlayerSheet], which keeps a single copy and
 * moves it between here and the expanded player as the sheet opens — a second copy fading in and
 * out underneath would give the effect away. What is left here is a gap of exactly the right size
 * for it to sit in.
 *
 * The bar of an episode being watched is the exception: where the artwork would sit it carries the
 * picture itself, still playing, in a frame of a picture's shape. Nothing travels then — that bar
 * opens the video screen and not the sheet — so the frame is the bar's own; see [MiniPicture].
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
 * @param video true while the bar is the video screen put away rather than the sheet. It then
 *   shows the picture where the artwork would be, and carries a small screen glyph beside the
 *   show's name: until the picture has a frame to show the bar looks much as it does for sound,
 *   and without the mark a tap that opens a picture instead of the sheet would be a surprise.
 * @param picture draws the player's picture into the modifier it is given; only called while
 *   [video] is true. A slot for the reason the video screen's is one: a preview, a test and a
 *   golden can put a plain box where a view bound to the player would be.
 */
@Composable
fun CollapsedPlayer(
    playback: PlaybackState,
    settings: PlaybackSettings,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
    video: Boolean = false,
    picture: @Composable (Modifier) -> Unit = {},
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
            if (video) {
                MiniPicture(playback = playback, picture = picture)
            } else {
                // The hole the shared artwork is drawn into.
                Spacer(modifier = Modifier.size(ArtworkSize.Mini.dimension))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playback.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs),
                ) {
                    if (video) {
                        // Decorative: the bar's own click label says what the tap opens.
                        Icon(
                            imageVector = Icons.Rounded.SmartDisplay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(VideoMarkSize),
                        )
                    }
                    Text(
                        text = playback.showTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = onSkipBack) {
                SkipGlyph(skipMs = settings.skipBackMs, forward = false)
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
                SkipGlyph(skipMs = settings.skipForwardMs, forward = true)
            }
        }
    }
}

/**
 * The picture of the episode being watched, at the size of the bar.
 *
 * As tall as the artwork it stands in for and as wide as a picture of that height, so the bar keeps
 * its height and the frame costs the title a little width. A picture of another shape sits inside
 * the frame on black, as it does on the video screen.
 *
 * The episode's artwork covers the frame until the picture has a frame of its own to show — while
 * it is being fetched, after the app comes back to the front, between one episode and the next —
 * so the bar is never a black box and never a still of the episode before. For a YouTube episode
 * that artwork is the video's own thumbnail, which makes it the poster it looks like.
 *
 * @param playback what the player is doing; says whether there is a picture and what shape it is.
 * @param picture draws the picture.
 * @param modifier layout modifier.
 */
@Composable
private fun MiniPicture(
    playback: PlaybackState,
    picture: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(
                width = ArtworkSize.Mini.dimension * MINI_PICTURE_ASPECT_RATIO,
                height = ArtworkSize.Mini.dimension,
            )
            .clip(MegaPodcastPlayerTheme.shapes.artwork)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        picture(Modifier.aspectRatio(playback.videoAspectRatio ?: MINI_PICTURE_ASPECT_RATIO))
        // By the first frame drawn on this texture, not by the decoder's size, which is still
        // there from the video screen when the picture has only just been handed to the bar.
        if (!playback.pictureReady) {
            PodcastArtwork(
                url = playback.artworkUrl,
                modifier = Modifier.fillMaxSize(),
                // The frame is already clipped; a second, squarer mask would show its corners.
                shape = RectangleShape,
            )
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

/** The video mark's side; small enough to sit inside the show line's own height at any scale. */
private val VideoMarkSize: Dp = 14.dp

/** The shape of the bar's picture frame; every YouTube rendition this app plays is 16:9. */
private const val MINI_PICTURE_ASPECT_RATIO = 16f / 9f

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
internal fun CollapsedPlayerPreview() {
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

/**
 * The bar of an episode being watched: the picture where the artwork would be — a grey box here,
 * as in the video screen's preview — and the mark that says a tap opens it. At 200 % text too,
 * where the mark has to stay inside a line that has grown around it.
 */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun CollapsedPlayerVideoPreview() {
    MegaPodcastPlayerTheme {
        CollapsedPlayer(
            playback = previewPlayback.copy(
                youTubeVideoId = "niTJ2221aS8",
                videoQuality = VideoQuality(PREVIEW_PICTURE_HEIGHT),
                videoWidth = PREVIEW_PICTURE_WIDTH,
                videoHeight = PREVIEW_PICTURE_HEIGHT,
                pictureReady = true,
            ),
            settings = PlaybackSettings(),
            onPlayPause = {},
            onSkipBack = {},
            onSkipForward = {},
            modifier = Modifier.height(collapsedPlayerHeight()),
            video = true,
            picture = { pictureModifier ->
                Box(modifier = pictureModifier.background(Color.DarkGray))
            },
        )
    }
}

/** The sample picture's size in the video bar's preview: a 720p frame. */
private const val PREVIEW_PICTURE_WIDTH = 1280

/** The sample picture's rendition height. */
private const val PREVIEW_PICTURE_HEIGHT = 720

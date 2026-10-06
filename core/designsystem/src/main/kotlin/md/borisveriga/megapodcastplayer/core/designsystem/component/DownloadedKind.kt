package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.format.formatVideoQuality

/** One half of an episode that is fully on the phone. */
sealed interface DownloadedKind {

    /** The episode's sound. */
    data object Audio : DownloadedKind

    /**
     * The episode's picture.
     *
     * @property download the finished video download, which names its rendition.
     */
    data class Video(val download: VideoDownload) : DownloadedKind
}

/**
 * Which halves of an episode are fully on the phone, sound first.
 *
 * Only finished ones: a badge says "this is here", and a transfer or a failure is not — a row's
 * metadata line or its progress ring already says what is happening to those.
 *
 * @param audio the episode's audio download state.
 * @param video its video download, if it has one.
 * @return the badges to draw; empty when nothing has finished.
 */
fun downloadedKinds(audio: DownloadState, video: VideoDownload?): List<DownloadedKind> =
    listOfNotNull(
        DownloadedKind.Audio.takeIf { audio == DownloadState.COMPLETED },
        video?.takeIf { it.isComplete }?.let(DownloadedKind::Video),
    )

/**
 * A small pill naming one downloaded half: *Audio*, or *Video · 720p*, and spoken as where it is.
 *
 * Shaped like the library's source badge — a pill on the highest surface container, a glyph and a
 * word — because it is the same kind of fact: what this row is. Drawn by Downloads, at the end of
 * a finished row, and by a show's page, under an episode's metadata, so the one fact wears one
 * shape wherever a list is read down for what will play without a connection.
 *
 * Where [onPlayVideo] is given, the video one is also the way to watch it, and is a node of its
 * own a screen reader can land on. It stays a pill rather than becoming a button-shaped button,
 * so the two halves still read as a pair.
 *
 * @param kind the half it names.
 * @param modifier layout modifier.
 * @param onPlayVideo plays the episode as video from the video badge, or null where the badge is
 *   only a mark — a show's page, whose rows carry their own watch button.
 */
@Composable
fun DownloadedKindBadge(
    kind: DownloadedKind,
    modifier: Modifier = Modifier,
    onPlayVideo: (() -> Unit)? = null,
) {
    val quality = (kind as? DownloadedKind.Video)?.let { formatVideoQuality(it.download.quality.height) }
    val (icon, label, spoken) = when {
        quality == null -> Triple(
            Icons.Rounded.Headphones,
            stringResource(R.string.designsystem_badge_audio),
            stringResource(R.string.designsystem_badge_audio_spoken),
        )

        // The screen glyph, which means watching.
        else -> Triple(
            Icons.Rounded.SmartDisplay,
            stringResource(R.string.designsystem_badge_video, quality),
            stringResource(R.string.designsystem_badge_video_spoken, quality),
        )
    }
    val playVideoLabel = stringResource(R.string.designsystem_badge_play_video)
    val playVideo = onPlayVideo?.takeIf { quality != null }
    Row(
        modifier = modifier
            .clip(MegaPodcastPlayerTheme.shapes.pill)
            // The click comes before the cleared semantics, so the badge is a node of its own a
            // screen reader can land on, rather than one more fact merged into the row around it.
            .then(
                if (playVideo != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = playVideoLabel,
                        onClick = playVideo,
                    )
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics { contentDescription = spoken }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(
                horizontal = MegaPodcastPlayerTheme.spacing.sm,
                vertical = MegaPodcastPlayerTheme.spacing.xxs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(BADGE_ICON_SIZE),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The badge glyph, sized to the label type beside it. */
private val BADGE_ICON_SIZE = 14.dp

@ThemePreviews
@Composable
internal fun DownloadedKindBadgePreview() {
    MegaPodcastPlayerTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs)) {
            DownloadedKindBadge(DownloadedKind.Audio)
            DownloadedKindBadge(
                DownloadedKind.Video(VideoDownload(VideoQuality(720), DownloadState.COMPLETED, 100f)),
            )
        }
    }
}

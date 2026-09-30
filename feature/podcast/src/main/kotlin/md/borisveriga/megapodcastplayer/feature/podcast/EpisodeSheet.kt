package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.roundToInt
import md.borisveriga.megapodcastplayer.core.common.format.formatDuration
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.common.format.formatPublishedDate
import md.borisveriga.megapodcastplayer.core.common.format.formatRemaining
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapters
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtwork
import md.borisveriga.megapodcastplayer.core.designsystem.component.RichText
import md.borisveriga.megapodcastplayer.core.designsystem.component.WavyProgressLine
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter

/**
 * One episode, read rather than played.
 *
 * The gap this closes is the largest one in the app: an episode could be played, queued and
 * downloaded, and never *read*. Its description — often a page of links, credits and a timestamped
 * chapter list — was parsed by the feed reader, stored on the row, and displayed by no screen at
 * all, and the chapters with it.
 *
 * Everything about the episode is here, in the order a person asks for it: what it is, and what to
 * do with it — play it, or keep it — then what is in it and what it says. A YouTube episode can be
 * played and kept two ways, as sound or as video, so it gets a pair of play buttons and a download
 * button for each; any other episode has only its sound, and one of each. Queueing is the row's
 * swipe, not the sheet's.
 *
 * Scrolls as one column rather than putting the notes in a scroller of their own: nested scrolling
 * inside a draggable sheet is how a description ends up impossible to read on a small screen.
 *
 * @param episode the episode, as stored.
 * @param showTitle the owning show, which the episode's own title rarely repeats.
 * @param artworkUrl the episode's own artwork, or the show's.
 * @param chapters the episode's chapters, once resolved.
 * @param isChaptersLoading true while they are still being looked for.
 * @param now the reference point for the published date, injected so previews and tests are stable.
 * @param video the episode's picture — its download and the renditions it comes in — or null when
 *   it has none, which hides every video action.
 * @param onPlay plays the episode's sound from where it was left.
 * @param onPlayChapter plays it from a chapter's start.
 * @param onToggleDownload downloads, cancels or deletes the audio — whichever its state means.
 * @param videoActions what the video buttons and the quality dialog do; unused when [video] is null.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 */
// ModalBottomSheet is still experimental in material3 1.4.0 and is the only modal sheet there is;
// the opt-in is scoped to the one composable that opens it, and MegaPodcastPlayerBottomSheet is
// where a replacement would be swapped in.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSheet(
    episode: Episode,
    showTitle: String,
    artworkUrl: String?,
    chapters: EpisodeChapters,
    isChaptersLoading: Boolean,
    now: Instant,
    video: EpisodeVideo?,
    onPlay: () -> Unit,
    onPlayChapter: (Chapter) -> Unit,
    onToggleDownload: () -> Unit,
    videoActions: EpisodeVideoActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal)
                .padding(bottom = MegaPodcastPlayerTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.lg),
        ) {
            EpisodeIdentity(
                episode = episode,
                showTitle = showTitle,
                artworkUrl = artworkUrl,
                now = now,
            )

            EpisodeActions(
                episode = episode,
                video = video,
                onPlay = onPlay,
                onToggleDownload = onToggleDownload,
                videoActions = videoActions,
            )

            if (isChaptersLoading) {
                // The hairline, not a spinner: looking for chapters is work the app started on its
                // own, and the app's rule is that automatic work is a hairline.
                WavyProgressLine(
                    contentDescription = stringResource(R.string.episode_chapters_loading),
                )
            }

            if (chapters.isNotEmpty) {
                ChapterList(
                    chapters = chapters,
                    positionMs = episode.positionMs,
                    onPlayChapter = onPlayChapter,
                )
            }

            if (episode.description.isNotBlank()) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.episode_notes),
                    style = MaterialTheme.typography.titleSmall,
                )
                // The publisher's own markup, links and all. This is the whole reason the sheet
                // exists: a link mentioned in an episode is unreachable if the notes are not shown.
                RichText(html = episode.description)
            }
        }
    }
}

/**
 * What the episode *is*: artwork, title, show, and the date-and-duration line.
 *
 * @param episode the episode.
 * @param showTitle the owning show.
 * @param artworkUrl artwork for the leading square.
 * @param now reference point for the published date.
 * @param modifier layout modifier.
 */
@Composable
private fun EpisodeIdentity(
    episode: Episode,
    showTitle: String,
    artworkUrl: String?,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
    ) {
        PodcastArtwork(url = artworkUrl, size = ArtworkSize.Row)

        Column(verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xxs)) {
            Text(
                text = showTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleMedium,
                // Not truncated to one line, unlike a row: the sheet is where the whole title is
                // finally allowed to be read.
                maxLines = MAX_TITLE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = episode.metadataLine(now = now),
                style = MegaPodcastPlayerTheme.type.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the sheet knows about an episode's picture.
 *
 * @property download the video kept on the phone, or on its way; null when there is none.
 * @property qualities the renditions on offer, lowest first; null until asked for and answered.
 * @property qualitiesFailed true when asking for them failed.
 */
data class EpisodeVideo(
    val download: VideoDownload? = null,
    val qualities: List<VideoQuality>? = null,
    val qualitiesFailed: Boolean = false,
)

/**
 * What the sheet's video actions do, gathered so the sheet's signature stays readable.
 *
 * @property onPlay plays the episode and opens it as video.
 * @property onRequestQualities asks which renditions the video comes in; called as the download
 *   dialog opens.
 * @property onDownload downloads the video at a rendition.
 * @property onRemoveDownload deletes the downloaded video, or cancels one on its way.
 */
class EpisodeVideoActions(
    val onPlay: () -> Unit,
    val onRequestQualities: () -> Unit,
    val onDownload: (VideoQuality) -> Unit,
    val onRemoveDownload: () -> Unit,
)

/**
 * The sheet's verbs: play, then keep.
 *
 * Play comes first and on its own line, because it is what the sheet is opened on the way to nine
 * times in ten; for an episode with a picture the line is split between sound and video, equal
 * halves, since neither is the lesser way to take it. The downloads follow one per line, full
 * width, so each can say in words what state its copy is in.
 *
 * @param episode the episode, which decides the labels.
 * @param video its picture, or null when it has none.
 * @param onPlay plays the sound.
 * @param onToggleDownload downloads, cancels or deletes the audio.
 * @param videoActions the video buttons' handlers.
 * @param modifier layout modifier.
 */
@Composable
private fun EpisodeActions(
    episode: Episode,
    video: EpisodeVideo?,
    onPlay: () -> Unit,
    onToggleDownload: () -> Unit,
    videoActions: EpisodeVideoActions,
    modifier: Modifier = Modifier,
) {
    // Saveable, so unfolding the phone with the dialog open does not close it.
    var qualityDialogOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
    ) {
        if (video == null) {
            ActionButton(
                icon = Icons.Rounded.PlayArrow,
                label = episode.playLabel(),
                onClick = onPlay,
                filled = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
            ) {
                ActionButton(
                    icon = Icons.Rounded.Headphones,
                    label = stringResource(R.string.episode_play_audio),
                    onClick = onPlay,
                    filled = true,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    icon = Icons.Rounded.SmartDisplay,
                    label = stringResource(R.string.episode_play_video),
                    onClick = videoActions.onPlay,
                    filled = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ActionButton(
            icon = episode.downloadIcon(),
            label = stringResource(episode.downloadLabelRes(hasVideo = video != null)),
            onClick = onToggleDownload,
            modifier = Modifier.fillMaxWidth(),
        )

        if (video != null) {
            ActionButton(
                icon = Icons.Rounded.OndemandVideo,
                label = videoDownloadLabel(video.download),
                onClick = {
                    videoActions.onRequestQualities()
                    qualityDialogOpen = true
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (qualityDialogOpen && video != null) {
        VideoDownloadDialog(
            qualities = video.qualities,
            failed = video.qualitiesFailed,
            download = video.download,
            onDownload = { quality ->
                qualityDialogOpen = false
                videoActions.onDownload(quality)
            },
            onRemoveDownload = {
                qualityDialogOpen = false
                videoActions.onRemoveDownload()
            },
            onDismiss = { qualityDialogOpen = false },
        )
    }
}

/**
 * One action button; the caller decides its width.
 *
 * @param icon the glyph.
 * @param label the words, which are also what a screen reader says.
 * @param onClick the handler.
 * @param modifier layout modifier.
 * @param filled true for a play button, which is filled; downloads are tonal, a step quieter.
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Icon(imageVector = icon, contentDescription = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = MegaPodcastPlayerTheme.spacing.sm),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (filled) {
        Button(onClick = onClick, modifier = modifier) { content() }
    } else {
        FilledTonalButton(onClick = onClick, modifier = modifier) { content() }
    }
}

/**
 * What the single play button says for an episode with no picture: play, continue, or play again.
 *
 * "Continue · 12 min left" wherever the number is known; the number is what decides whether to
 * press it now or later.
 */
@Composable
private fun Episode.playLabel(): String {
    val remaining = formatRemaining(LocalResources.current, durationMs, positionMs)
    return when {
        isInProgress && remaining != null ->
            stringResource(R.string.episode_continue_with_remaining, remaining)

        isInProgress -> stringResource(R.string.episode_continue)

        isPlayed -> stringResource(R.string.episode_play_again)

        else -> stringResource(R.string.episode_play)
    }
}

/**
 * What the *Download video* button says: the action while there is no video, its state once there
 * is one — the tap opens the dialog either way, where it can be changed or deleted.
 *
 * @param download the episode's downloaded video, if any.
 */
@Composable
private fun videoDownloadLabel(download: VideoDownload?): String {
    val quality = download?.let { stringResource(R.string.episode_video_quality, it.quality.height) }
    return when (download?.state) {
        null, DownloadState.NOT_DOWNLOADED, DownloadState.FAILED ->
            stringResource(R.string.episode_download_video)

        DownloadState.QUEUED -> stringResource(R.string.episode_video_download_waiting, quality.orEmpty())

        DownloadState.DOWNLOADING -> stringResource(
            R.string.episode_video_download_progress,
            quality.orEmpty(),
            download.percent.roundToInt(),
        )

        DownloadState.COMPLETED -> stringResource(R.string.episode_video_downloaded, quality.orEmpty())
    }
}

/**
 * The episode's chapters, each one a place to start from.
 *
 * Parsed, stored and shown nowhere until now — the app shipped with four chapter sources and no
 * chapter list. The one containing the current position is marked, so the sheet doubles as "where
 * am I" for an episode already in progress.
 *
 * @param chapters the chapters and where they came from.
 * @param positionMs the stored playback position, for marking the current chapter.
 * @param onPlayChapter plays the episode from a chapter's start.
 * @param modifier layout modifier.
 */
@Composable
private fun ChapterList(
    chapters: EpisodeChapters,
    positionMs: Long,
    onPlayChapter: (Chapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = chapters.chapters.indexOfLast { it.startMs <= positionMs }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(
            text = pluralStringResource(
                R.plurals.episode_chapters,
                chapters.chapters.size,
                chapters.chapters.size,
            ),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = MegaPodcastPlayerTheme.spacing.md),
        )

        chapters.chapters.forEachIndexed { index, chapter ->
            ChapterRow(
                chapter = chapter,
                isCurrent = index == currentIndex && positionMs > 0L,
                onClick = { onPlayChapter(chapter) },
            )
        }
    }
}

/**
 * One chapter: its start, its name, and a tap that seeks there.
 *
 * @param chapter the chapter.
 * @param isCurrent whether the stored position falls inside it.
 * @param onClick plays the episode from [Chapter.startMs].
 * @param modifier layout modifier.
 */
@Composable
private fun ChapterRow(
    chapter: Chapter,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = MegaPodcastPlayerTheme.spacing.minTouchTarget)
            .padding(vertical = MegaPodcastPlayerTheme.spacing.sm)
            .semantics(mergeDescendants = true) { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
    ) {
        Text(
            text = formatPosition(chapter.startMs),
            style = MegaPodcastPlayerTheme.type.numeric,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            // Fixed, so the titles line up rather than stepping in and out as the timecodes cross
            // an hour. The type style is already tabular, so only the column has to be reserved.
            modifier = Modifier.width(ChapterTimeWidth),
        )
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The date-and-duration line under an episode's title.
 *
 * @param now the reference point for the relative date.
 * @return e.g. `2 days ago · 1 h 23 min`, or whichever half is known.
 */
@Composable
private fun Episode.metadataLine(now: Instant): String {
    // `LocalResources` rather than `LocalContext.current.resources`, so a configuration change —
    // a locale switch included — invalidates the read and the line is formatted again.
    val resources = LocalResources.current
    return listOfNotNull(
        formatPublishedDate(resources, publishedAt, now),
        formatDuration(resources, durationMs),
    ).joinToString(SEPARATOR)
}

/** The glyph for the audio download button, which depends on what the copy is doing. */
private fun Episode.downloadIcon(): ImageVector = when (downloadState) {
    DownloadState.COMPLETED -> Icons.Rounded.Delete
    DownloadState.QUEUED, DownloadState.DOWNLOADING -> Icons.Rounded.Close
    DownloadState.NOT_DOWNLOADED, DownloadState.FAILED -> Icons.Rounded.FileDownload
}

/**
 * The words for the audio download button.
 *
 * The swipe's own three words for an episode that is only sound; for one that is also video they
 * say *audio*, because beside a *Download video* button a bare *Download* no longer names a thing.
 *
 * @param hasVideo whether the episode has a picture too.
 */
private fun Episode.downloadLabelRes(hasVideo: Boolean): Int = when (downloadState) {
    DownloadState.NOT_DOWNLOADED, DownloadState.FAILED ->
        if (hasVideo) R.string.episode_download_audio else R.string.podcast_action_download

    DownloadState.QUEUED, DownloadState.DOWNLOADING ->
        if (hasVideo) R.string.episode_cancel_audio_download else R.string.podcast_action_cancel_download

    DownloadState.COMPLETED ->
        if (hasVideo) R.string.episode_delete_audio_download else R.string.podcast_action_delete_download
}

/** Between the date and the duration. */
private const val SEPARATOR = " · "

/** How many lines of title the sheet allows before truncating. */
private const val MAX_TITLE_LINES = 4

/** Width reserved for a chapter's timecode, sized for `1:23:45`. */
private val ChapterTimeWidth: Dp = 64.dp

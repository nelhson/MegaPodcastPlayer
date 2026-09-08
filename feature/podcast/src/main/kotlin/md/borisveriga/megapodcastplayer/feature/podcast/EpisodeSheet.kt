package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter

/**
 * One episode, read rather than played.
 *
 * The gap this closes is the largest one in the app: an episode could be played, queued and
 * downloaded, and never *read*. Its description — often a page of links, credits and a timestamped
 * chapter list — was parsed by the feed reader, stored on the row, and displayed by no screen at
 * all, and the chapters with it.
 *
 * Everything about the episode is here, in the order a person asks for it: what it is, what it says,
 * what is in it, and only then what to do with it. The actions include the three the row cannot
 * carry — add to the end of the queue, mark played, share — because a row has space for two and a
 * sheet has space for all of them.
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
 * @param onPlay plays the episode from where it was left.
 * @param onPlayChapter plays it from a chapter's start.
 * @param onPlayNext queues it to play after whatever is playing now.
 * @param onAddToQueue puts it at the end of the queue.
 * @param onToggleDownload downloads, cancels or deletes — whichever the current state means.
 * @param onSetPlayed marks it played, or puts it back to unplayed.
 * @param onShare hands the episode to the system share sheet.
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
    onPlay: () -> Unit,
    onPlayChapter: (Chapter) -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleDownload: () -> Unit,
    onSetPlayed: (Boolean) -> Unit,
    onShare: () -> Unit,
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

            PrimaryAction(episode = episode, onPlay = onPlay)

            EpisodeActions(
                episode = episode,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onToggleDownload = onToggleDownload,
                onSetPlayed = onSetPlayed,
                onShare = onShare,
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
 * The one full-width button: play, or continue.
 *
 * Larger and alone, because it is what the sheet is opened *on the way to* nine times in ten. The
 * rest of the verbs sit under it as chips, which is the shape that says "and these are the others".
 *
 * @param episode the episode.
 * @param onPlay the handler.
 * @param modifier layout modifier.
 */
@Composable
private fun PrimaryAction(
    episode: Episode,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = formatRemaining(LocalResources.current, episode.durationMs, episode.positionMs)
    val label = when {
        // "Continue · 12 min left" wherever the number is known; the number is what decides whether
        // to press it now or later.
        episode.isInProgress && remaining != null ->
            stringResource(R.string.episode_continue_with_remaining, remaining)

        episode.isInProgress -> stringResource(R.string.episode_continue)

        episode.isPlayed -> stringResource(R.string.episode_play_again)

        else -> stringResource(R.string.episode_play)
    }

    Button(
        onClick = onPlay,
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = MegaPodcastPlayerTheme.spacing.sm),
        )
    }
}

/**
 * Everything else the episode can be told to do.
 *
 * Chips rather than a menu: they are all one tap, they all fit, and a menu would hide the two the
 * app had nowhere to put — *add to queue* and *mark played* — behind a second tap on the screen
 * built to reveal them.
 *
 * @param episode the episode, which decides the download and played labels.
 * @param onPlayNext queues it next.
 * @param onAddToQueue queues it last.
 * @param onToggleDownload downloads, cancels or deletes.
 * @param onSetPlayed marks it played or unplayed.
 * @param onShare shares it.
 * @param modifier layout modifier.
 */
@Composable
private fun EpisodeActions(
    episode: Episode,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleDownload: () -> Unit,
    onSetPlayed: (Boolean) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScrollIfNeeded(),
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
    ) {
        ActionChip(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            label = stringResource(R.string.podcast_action_play_next),
            onClick = onPlayNext,
        )
        ActionChip(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            label = stringResource(R.string.episode_action_add_to_queue),
            onClick = onAddToQueue,
        )
        ActionChip(
            icon = episode.downloadIcon(),
            label = stringResource(episode.downloadLabelRes()),
            onClick = onToggleDownload,
        )
        ActionChip(
            icon = if (episode.isPlayed) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll,
            label = stringResource(
                if (episode.isPlayed) {
                    R.string.podcast_action_mark_unplayed
                } else {
                    R.string.podcast_action_mark_played
                },
            ),
            onClick = { onSetPlayed(!episode.isPlayed) },
        )
        ActionChip(
            icon = Icons.Rounded.Share,
            label = stringResource(R.string.episode_action_share),
            onClick = onShare,
        )
    }
}

/**
 * One action chip.
 *
 * @param icon the glyph.
 * @param label the words, which are also what a screen reader says.
 * @param onClick the handler.
 */
@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
    )
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

/** The glyph for the download chip, which depends on what the copy is currently doing. */
private fun Episode.downloadIcon(): ImageVector = when (downloadState) {
    DownloadState.COMPLETED -> Icons.Rounded.Delete
    else -> Icons.Rounded.FileDownload
}

/** The words for the download chip; the same five states the swipe action names. */
private fun Episode.downloadLabelRes(): Int = when (downloadState) {
    DownloadState.NOT_DOWNLOADED,
    DownloadState.FAILED,
    -> R.string.podcast_action_download

    DownloadState.QUEUED,
    DownloadState.DOWNLOADING,
    -> R.string.podcast_action_cancel_download

    DownloadState.COMPLETED -> R.string.podcast_action_delete_download
}

/**
 * Lets the action chips scroll sideways when they no longer fit.
 *
 * Five chips fit a phone at the default text size and do not fit at 200 %, and a chip pushed off
 * the edge is an action that has silently stopped existing. Scrolling is what the settings screen's
 * own chip rows already do.
 */
@Composable
private fun Modifier.horizontalScrollIfNeeded(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

/** Between the date and the duration. */
private const val SEPARATOR = " · "

/** How many lines of title the sheet allows before truncating. */
private const val MAX_TITLE_LINES = 4

/** Width reserved for a chapter's timecode, sized for `1:23:45`. */
private val ChapterTimeWidth: Dp = 64.dp

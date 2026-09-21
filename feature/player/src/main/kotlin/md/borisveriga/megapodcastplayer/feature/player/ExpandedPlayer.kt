package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BedtimeOff
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import md.borisveriga.megapodcastplayer.core.common.format.formatCountdown
import md.borisveriga.megapodcastplayer.core.common.format.formatDuration
import md.borisveriga.megapodcastplayer.core.common.format.formatEndsAt
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.designsystem.component.DownloadButton
import md.borisveriga.megapodcastplayer.core.designsystem.component.LabelledWaveScrubber
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseButton
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseSize
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.SleepTimerState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

/**
 * The player fully open, in whichever of its two shapes the window has room for.
 *
 * Like [CollapsedPlayer] this draws no artwork — [PlayerSheet] owns the single copy that travels
 * between the two — but it does leave room for it, along with room for the sheet's own header.
 *
 * Both shapes are the same two blocks; what changes is whether they are stacked or set beside each
 * other. What the episode *is* — artwork, title, show — is one block, and what the user *does* —
 * scrub, play, skip, open the queue — is the other.
 *
 * **Stacked**, which is every phone held upright: the episode at the top where the artwork lands,
 * the controls within reach of a thumb at the bottom, rather than stranded in the middle of the
 * screen with dead space under them. The column is scrollable and at least a screenful tall, so at
 * a large font scale the two blocks meet and the whole thing scrolls as one instead of clipping.
 *
 * **Side by side**, on a window wide enough for it — the inner display of a folded phone, a phone
 * laid on its side: the artwork takes the leading half and everything that is words or buttons
 * takes the trailing one. Stacked there, the artwork is the only thing that grows with the window,
 * which makes a square the size of a dinner plate with the transport pressed against the bottom
 * edge — the largest share of a large screen given to the one thing that gains least from it.
 *
 * @param uiState what to render.
 * @param heroArtworkSize how tall the artwork will be, so the right amount of room is left for it.
 * @param sideBySide whether to set the artwork beside the controls rather than above them. Decided
 *   by [PlayerSheet], which is the thing that measures the window.
 * @param onPlayPause play/pause handler.
 * @param onSeek absolute-seek handler, called once when the user releases the scrubber.
 * @param onSkipForward skip-ahead handler.
 * @param onSkipBack skip-back handler.
 * @param onSkipToNext next-episode handler.
 * @param onSkipToPrevious previous-episode handler.
 * @param onOpenSpeed opens the speed sheet.
 * @param onOpenSleepTimer opens the sleep timer sheet.
 * @param onToggleDownload starts, cancels, retries or deletes the episode's offline copy.
 * @param onMarkMoment saves a moment at the playhead.
 * @param onOpenMoments opens the list of this episode's moments.
 * @param onOpenQueue opens the queue screen.
 * @param modifier layout modifier.
 */
@Composable
fun ExpandedPlayer(
    uiState: PlayerUiState,
    heroArtworkSize: Dp,
    sideBySide: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onToggleDownload: () -> Unit,
    onMarkMoment: () -> Unit,
    onOpenMoments: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The two blocks as slots, built once and handed to whichever arrangement is in force. A
    // composable per shape, each taking the same fourteen parameters, would be two places for the
    // next control to be added to and one of them for it to be forgotten in.
    val heading: @Composable () -> Unit = { EpisodeHeading(uiState = uiState) }
    val controls: @Composable () -> Unit = {
        PlayerControls(
            uiState = uiState,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onSkipForward = onSkipForward,
            onSkipBack = onSkipBack,
            onSkipToNext = onSkipToNext,
            onSkipToPrevious = onSkipToPrevious,
            onOpenSpeed = onOpenSpeed,
            onOpenSleepTimer = onOpenSleepTimer,
            onToggleDownload = onToggleDownload,
            onMarkMoment = onMarkMoment,
            onOpenMoments = onOpenMoments,
            onOpenQueue = onOpenQueue,
        )
    }

    if (sideBySide) {
        WidePlayer(heading = heading, controls = controls, modifier = modifier)
    } else {
        TallPlayer(
            heroArtworkSize = heroArtworkSize,
            heading = heading,
            controls = controls,
            modifier = modifier,
        )
    }
}

/**
 * The stacked shape: the episode at the top, the controls at the bottom.
 *
 * @param heroArtworkSize how tall the hole left for the artwork is.
 * @param heading title, show and current chapter.
 * @param controls scrubber, transport, per-episode actions and the way into the queue.
 * @param modifier layout modifier.
 */
@Composable
private fun TallPlayer(
    heroArtworkSize: Dp,
    heading: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // `fillMaxSize` ahead of `verticalScroll` is what makes both true at once: the scroll
        // relaxes the maximum height to infinity but leaves the minimum at a full screen, so the
        // column is never shorter than the sheet and `SpaceBetween` has room to push against.
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = expandedHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The sheet's header and the shared artwork are drawn over this column, not in it.
            // Both gaps are at the top, so everything below flows normally.
            Spacer(
                modifier = Modifier.height(
                    expandedHeaderHeight + expandedArtworkTopGap + heroArtworkSize,
                ),
            )

            heading()
        }

        // The controls sit on a strip of the sheet's own surface, laid over the artwork backdrop
        // behind them. Cover art is arbitrary third-party imagery, so the contrast of a timecode or
        // a speed label against it cannot be reasoned about — the strip is what makes it a fact
        // again. It fades in from transparent at the top so the wash meets the page rather than
        // stopping against it in a hard line, and it runs full width, under the body's own side
        // padding, so it reads as the bottom of the screen rather than a floating card.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f),
                        CONTROL_STRIP_SOLID_AT to MaterialTheme.colorScheme.surfaceContainerHigh,
                        1f to MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
                .padding(horizontal = expandedHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            controls()

            Spacer(modifier = Modifier.height(MegaPodcastPlayerTheme.spacing.xl))
        }
    }
}

/**
 * The side-by-side shape: the artwork's half, then everything else.
 *
 * The leading half holds nothing at all. What is drawn there is the sheet's one travelling copy of
 * the artwork, centred by the same arithmetic that sizes it, so this side's whole job is to reserve
 * the room — the bargain [TallPlayer] makes with a `Spacer`, made here with a weight.
 *
 * The trailing half is a solid panel rather than the stacked shape's fading strip, and for the
 * reason the strip fades: a strip stops in the middle of a page, where a hard horizontal line would
 * read as a floating card, while an edge that runs the full height of the window reads as what it
 * is — a screen divided in two. Both exist to answer the same problem, that the blurred cover
 * behind them is arbitrary third-party imagery and a timecode's contrast against it cannot be
 * reasoned about.
 *
 * Centred, then scrolling: vertically centred while the controls fit, scrolling from the top once a
 * large font scale means they do not.
 *
 * @param heading title, show and current chapter.
 * @param controls scrubber, transport, per-episode actions and the way into the queue.
 * @param modifier layout modifier.
 */
@Composable
private fun WidePlayer(
    heading: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // The sheet's header strip is drawn over the top of both halves, so both start below it.
        modifier = modifier
            .fillMaxSize()
            .padding(top = expandedHeaderHeight),
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // Before the scroll, so the panel is the pane's full height rather than the
                // content's: a wash that stopped where the buttons do would be the floating card
                // the stacked shape's gradient exists to avoid.
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = expandedHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            heading()

            controls()
        }
    }
}

/**
 * What the episode is: its title, its show, and where in it the playhead currently is.
 *
 * @param uiState what to render.
 * @param modifier layout modifier.
 */
@Composable
private fun EpisodeHeading(
    uiState: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val playback = uiState.playback

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = playback.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.xl),
        )
        Text(
            text = playback.showTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.xs),
        )

        // Where in the episode you are, in words. The scrubber says it as a position and the
        // ticks say it as a shape; this is the only one of the three you can read at a glance
        // while walking, and it is the whole reason chapters are worth having.
        uiState.currentChapter?.let { chapter ->
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
            )
        }
    }
}

/**
 * What the user does: the scrubber, the transport, the per-episode actions and the queue.
 *
 * Emits its four children rather than wrapping them in a column of its own, so that the shape
 * around it decides their side padding and their ground — a fading strip at the bottom of a stacked
 * player, a full-height panel beside a wide one.
 *
 * @param uiState what to render.
 * @param onPlayPause play/pause handler.
 * @param onSeek absolute-seek handler, called once when the user releases the scrubber.
 * @param onSkipForward skip-ahead handler.
 * @param onSkipBack skip-back handler.
 * @param onSkipToNext next-episode handler.
 * @param onSkipToPrevious previous-episode handler.
 * @param onOpenSpeed opens the speed sheet.
 * @param onOpenSleepTimer opens the sleep timer sheet.
 * @param onToggleDownload starts, cancels, retries or deletes the episode's offline copy.
 * @param onMarkMoment saves a moment at the playhead.
 * @param onOpenMoments opens the list of this episode's moments.
 * @param onOpenQueue opens the queue screen.
 */
@Composable
private fun PlayerControls(
    uiState: PlayerUiState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onToggleDownload: () -> Unit,
    onMarkMoment: () -> Unit,
    onOpenMoments: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Scrubber(
        playback = uiState.playback,
        markers = uiState.chapterMarks,
        onSeek = onSeek,
    )

    TransportControls(
        playback = uiState.playback,
        settings = uiState.settings,
        hasChapters = uiState.chapters.isNotEmpty(),
        onPlayPause = onPlayPause,
        onSkipForward = onSkipForward,
        onSkipBack = onSkipBack,
        onSkipToNext = onSkipToNext,
        onSkipToPrevious = onSkipToPrevious,
    )

    SecondaryActions(
        uiState = uiState,
        onOpenSpeed = onOpenSpeed,
        onOpenSleepTimer = onOpenSleepTimer,
        onToggleDownload = onToggleDownload,
        onMarkMoment = onMarkMoment,
        onOpenMoments = onOpenMoments,
    )

    // Drawn at zero too. The link used to vanish with the queue, which meant the player never
    // mentioned that a queue existed to the one user who most needed telling: the one who has not
    // put anything in it.
    UpNextLink(count = uiState.upNext.size, onClick = onOpenQueue)
}

/**
 * The seek bar and its position labels.
 *
 * The app's own [LabelledWaveScrubber] rather than a Material `Slider`: this is the signature
 * control the whole palette was drawn around, and the screen it was designed for was the one screen
 * not using it. The wave travels while playing and flattens when paused, so the difference between
 * the two states is legible from across the room without reading the button.
 *
 * The right-hand label counts down (`-30:06`) instead of repeating the total duration. The total is
 * a fact about the file; what a listener wants from a glance at the player is how much of it is
 * left. When the player has not read a duration yet there is nothing to count down from, so the
 * label falls back to the em dash — a wrong number reads as fact, a dash reads as "not yet".
 *
 * Tapping it cycles through all three answers, because they are answers to three different
 * questions and only the first one has a good default. *How long is left* is the listener's
 * question, *how long is it* is the one asked before starting, and *when does it end* — computed at
 * the current speed — is the only one that is about the evening rather than about the episode.
 * The choice is not persisted: it is a glance, not a setting.
 *
 * The scrubber commits its drag on release rather than per frame, which is why nothing here holds a
 * drag position of its own any more: that logic moved into the component, where every caller gets it.
 *
 * @param playback current playback state.
 * @param markers chapter starts, as fractions, ticked along the rail.
 * @param onSeek called once, with the released position.
 * @param modifier layout modifier.
 */
@Composable
private fun Scrubber(
    playback: PlaybackState,
    markers: List<Float>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = playback.knownDurationMs
    val unknown = stringResource(R.string.player_unknown_duration)
    var label by rememberSaveable { mutableStateOf(TimeLabel.Remaining) }
    val remainingMs = durationMs?.minus(playback.positionMs)

    val remainingLabel = when (label) {
        TimeLabel.Remaining -> formatCountdown(durationMs, playback.positionMs)
        TimeLabel.Total -> durationMs?.let(::formatPosition)
        TimeLabel.EndsAt -> formatEndsAt(remainingMs, playback.speed)
    }

    LabelledWaveScrubber(
        positionMs = playback.positionMs,
        // Zero renders an inert, empty rail, which is the correct face for a duration not yet read.
        durationMs = durationMs ?: 0L,
        playing = playback.isPlaying,
        onSeek = onSeek,
        elapsedLabel = formatPosition(playback.positionMs),
        remainingLabel = remainingLabel ?: unknown,
        enabled = durationMs != null,
        markers = markers,
        onRemainingClick = { label = label.next() },
        remainingClickLabel = stringResource(R.string.player_time_label_cycle),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = MegaPodcastPlayerTheme.spacing.lg),
    )
}

/**
 * The three things the right-hand label can be.
 *
 * A cycle rather than three controls, because the label is one line of five characters and all
 * three answers belong in the same place. The order is by how often each is wanted.
 */
private enum class TimeLabel {
    /** How much of the episode is left, counting down. The default, and the listener's question. */
    Remaining,

    /** How long the episode is; what you ask before starting one. */
    Total,

    /** The time of day it will finish at, at the current speed. */
    EndsAt,
    ;

    /** The next one round the cycle. */
    fun next(): TimeLabel = entries[(ordinal + 1) % entries.size]
}

/**
 * Previous, skip back, play/pause, skip ahead, next.
 *
 * @param playback current playback state.
 * @param settings the user's skip intervals, which choose the button glyphs.
 * @param hasChapters whether the episode has chapters, which is what the outer two buttons move
 *   between when it does.
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
    hasChapters: Boolean,
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
            .padding(top = MegaPodcastPlayerTheme.spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same two buttons, meaning the thing the user would mean by them. An episode with
        // chapters is a list of segments and these move between segments; an episode without them
        // is one thing and they move between episodes. Only the spoken label changes, because only
        // a screen reader has to be told which — the chapter title above says it to everyone else.
        IconButton(onClick = onSkipToPrevious) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(
                    if (hasChapters) R.string.player_previous_chapter else R.string.player_previous,
                ),
            )
        }

        IconButton(onClick = onSkipBack) {
            Icon(
                imageVector = skipBackIcon(settings.skipBackMs),
                contentDescription = skipContentDescription(settings.skipBackMs, forward = false),
                modifier = Modifier.size(SkipGlyphSize),
            )
        }

        // The morphing control, at its hero size. Buffering no longer swaps the glyph for a
        // spinner — the shape keeps morphing on its own with the glyph dimmed, so the button stays
        // the same size in the same place at the moment the user is most likely to press it again.
        PlayPauseButton(
            playing = playback.isPlaying,
            onToggle = { onPlayPause() },
            size = PlayPauseSize.Hero,
            buffering = playback.isBuffering,
            modifier = Modifier.padding(horizontal = MegaPodcastPlayerTheme.spacing.md),
        )

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
                contentDescription = stringResource(
                    if (hasChapters) R.string.player_next_chapter else R.string.player_next,
                ),
            )
        }
    }
}

/**
 * What the user does to *this episode*, as opposed to what they do to playback.
 *
 * A row rather than three stranded controls, and below the transport controls rather than in them:
 * the five buttons above are all "move the playhead", and speed, bell and download are all "about
 * the thing being played". Mixing them would make a six-button transport row that is both crowded
 * and no longer one idea.
 *
 * The download button is drawn only when there is an episode to have a download; see
 * [PlayerUiState.download].
 *
 * @param uiState what to render.
 * @param onOpenSpeed opens the speed sheet.
 * @param onOpenSleepTimer opens the sleep timer sheet.
 * @param onToggleDownload starts, cancels, retries or deletes the episode's offline copy.
 * @param onMarkMoment saves a moment at the playhead.
 * @param onOpenMoments opens the list of this episode's moments.
 * @param modifier layout modifier.
 */
@Composable
private fun SecondaryActions(
    uiState: PlayerUiState,
    onOpenSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onToggleDownload: () -> Unit,
    onMarkMoment: () -> Unit,
    onOpenMoments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = MegaPodcastPlayerTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(
            MegaPodcastPlayerTheme.spacing.sm,
            Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The rate is its own label, which is why this is a text button and not a glyph. Its
        // spoken form has to say more, though: a button announced as "1.5x" is a fact, and this
        // one is a door.
        //
        // The dot is D-4's answer to a per-show speed: a rate that differs from the app's with
        // nothing on screen to explain it reads as a bug rather than as a setting. It carries no
        // description of its own — the button's already says it, because a decoration a screen
        // reader has to notice separately is not a decoration.
        val speed = formatSpeed(uiState.playback.speed)
        val speedDescription = if (uiState.hasShowSpeed) {
            stringResource(R.string.player_speed_for_show, speed)
        } else {
            stringResource(R.string.player_speed, speed)
        }
        TextButton(
            onClick = onOpenSpeed,
            modifier = Modifier.semantics {
                contentDescription = speedDescription
            },
        ) {
            Text(text = speed)
            if (uiState.hasShowSpeed) {
                Box(
                    modifier = Modifier
                        .padding(start = MegaPodcastPlayerTheme.spacing.xxs)
                        .size(SHOW_SPEED_DOT)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }

        SleepTimerButton(sleep = uiState.sleep, onClick = onOpenSleepTimer)

        MarkMomentButton(
            count = uiState.momentCount,
            onClick = onMarkMoment,
            onCountClick = onOpenMoments,
        )

        uiState.download?.let { download ->
            DownloadButton(
                state = download.state,
                progressPercent = download.percent,
                onClick = onToggleDownload,
            )
        }
    }
}

/**
 * The one "stop later" control.
 *
 * It replaces the bell, which said only "at the end of this episode" and said it with a glyph that
 * meant notifications. A running timer wears the accent and *says the number*: how long is left is
 * the fact someone lying in the dark actually wants, and it is the difference between a control
 * that is on and one that is on for another eighteen minutes.
 *
 * @param sleep what the timer is doing.
 * @param onClick opens the sheet.
 * @param modifier layout modifier.
 */
@Composable
private fun SleepTimerButton(
    sleep: SleepTimerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = formatDuration(LocalResources.current, sleep.remainingMs)
    val description = when {
        remaining != null -> stringResource(R.string.player_sleep_armed, remaining)
        sleep.isEndOfEpisode -> stringResource(R.string.player_sleep_armed_end_of_episode)
        sleep.endOfChapterIndex != null -> stringResource(R.string.player_sleep_armed_end_of_chapter)
        else -> stringResource(R.string.player_sleep_arm)
    }

    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            role = Role.Button
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = if (sleep.isArmed) {
                    Icons.Rounded.Bedtime
                } else {
                    Icons.Rounded.BedtimeOff
                },
                // Announced by the row above, which carries the remaining time as well as the verb.
                contentDescription = null,
                tint = if (sleep.isArmed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (remaining != null) {
            Text(
                text = remaining,
                style = MegaPodcastPlayerTheme.type.numeric,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = MegaPodcastPlayerTheme.spacing.xs),
            )
        }
    }
}

/**
 * The button that saves a moment, with a count of the ones already in this episode.
 *
 * The count is the whole reason this is not a bare icon. Marking a moment produces no visible
 * change to the episode, the player or the artwork — the user presses a button and, but for a
 * snackbar that is gone in four seconds, nothing happens. A number that goes up is the standing
 * evidence that the button works, and it doubles as the answer to "did I already mark this?".
 *
 * TalkBack is told the count in words rather than left to read a bare digit next to an icon, since
 * a "2" beside a bookmark could as easily be a chapter number.
 *
 * The number beside it is now a control of its own: it opens the list of what has been marked. A
 * count the app shows and then refuses to open is a strange thing — the marks could only be read on
 * another screen, which is a long way to go to hear a sentence again.
 *
 * @param count how many moments this episode already has.
 * @param onClick saves one at the playhead.
 * @param onCountClick opens the list; only reachable when there is a number to tap.
 * @param modifier layout modifier.
 */
@Composable
private fun MarkMomentButton(
    count: Int,
    onClick: () -> Unit,
    onCountClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = if (count > 0) {
        pluralStringResource(R.plurals.player_moment_mark_with_count, count, count)
    } else {
        stringResource(R.string.player_moment_mark)
    }

    val listLabel = stringResource(R.string.player_moments_open)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClick,
            // On the button rather than on the row, now that the row holds two controls: a merged
            // node would have swallowed the count's own tap target along with its label.
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(
                imageVector = Icons.Rounded.BookmarkAdd,
                // Announced by the button, which carries the count as well as the verb.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MegaPodcastPlayerTheme.type.numeric,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = listLabel, onClick = onCountClick)
                    .padding(
                        horizontal = MegaPodcastPlayerTheme.spacing.sm,
                        vertical = MegaPodcastPlayerTheme.spacing.xs,
                    ),
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
 * @param count how many episodes follow the one playing; zero says so rather than hiding the link.
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
            .padding(vertical = MegaPodcastPlayerTheme.spacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (count == 0) {
                stringResource(R.string.player_queue_empty_summary)
            } else {
                pluralStringResource(R.plurals.player_queue_summary, count, count)
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (count == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
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

/** Gap between the header and the top of the artwork, in the stacked shape. */
internal val expandedArtworkTopGap: Dp = 8.dp

/**
 * Side padding for the expanded body.
 *
 * The screen token, not a hand-written 20 dp. The player was the one screen whose edges visibly
 * disagreed with every other one.
 */
internal val expandedHorizontalPadding: Dp
    @Composable get() = MegaPodcastPlayerTheme.spacing.screenHorizontal

/**
 * The shape the expanded player is in, and where in it the hero artwork lands.
 *
 * @property sideBySide whether the artwork is set beside the controls rather than above them.
 * @property heroSize how large the artwork is when the sheet is fully open.
 * @property heroLeft where its leading edge lands then.
 * @property heroTop where its top edge lands then.
 */
internal data class ExpandedPlayerLayout(
    val sideBySide: Boolean,
    val heroSize: Dp,
    val heroLeft: Dp,
    val heroTop: Dp,
)

/**
 * Works out the four of them, which are one decision taken in order.
 *
 * How wide the window is says whether the artwork gets a half of it or the whole; how much room
 * that leaves says how big the artwork may be; how big it is says where its corner goes.
 *
 * Arithmetic rather than layout, and pure, so that the shapes can be asserted at sizes there is no
 * screenshot of — `ExpandedPlayerLayoutTest` walks six windows this app will meet and the goldens
 * cannot afford one image each of.
 *
 * @param windowWidth how wide the sheet is; it is given the whole window.
 * @param contentHeight how tall it is, less the system bars the expanded sheet clears for itself.
 * @return where everything goes.
 */
internal fun expandedPlayerLayout(windowWidth: Dp, contentHeight: Dp): ExpandedPlayerLayout {
    val sideBySide = windowWidth >= SideBySideWidth
    val paneWidth = if (sideBySide) windowWidth / 2 else windowWidth
    // The room under the header strip. Coerced, because a window shorter than its own header is
    // not a layout to reason about but `Modifier.size` still refuses a negative number.
    val paneHeight = (contentHeight - expandedHeaderHeight).coerceAtLeast(0.dp)
    val heroSize = (paneWidth * HERO_ARTWORK_WIDTH_FRACTION)
        .coerceAtMost(paneHeight * HERO_ARTWORK_HEIGHT_FRACTION)

    return ExpandedPlayerLayout(
        sideBySide = sideBySide,
        heroSize = heroSize,
        heroLeft = (paneWidth - heroSize) / 2,
        // Stacked, the artwork hangs from the header with the titles flowing under it, so its top
        // is a constant. Side by side there is nothing under it to leave room for, so it is
        // centred in the half it has to itself.
        heroTop = if (sideBySide) {
            expandedHeaderHeight + (paneHeight - heroSize) / 2
        } else {
            expandedHeaderHeight + expandedArtworkTopGap
        },
    )
}

/**
 * How wide the window has to be before the artwork is set beside the controls.
 *
 * Material 3's expanded width breakpoint, named by `androidx.window` rather than written out here,
 * so the player changes shape at the width the app's other adaptive surfaces change at. The Fold 7
 * opened out is over it and folded is well under, which is the whole point: one phone, two windows,
 * and this is the number between them.
 */
private val SideBySideWidth: Dp = WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND.dp

/**
 * Fraction of *its half of the window* the artwork occupies when fully expanded.
 *
 * Its half, not the window. Stacked, the artwork has the whole width and the two are the same
 * number; side by side it has the leading half, and the same fraction then describes the same
 * proportion of the room it was actually given.
 */
internal const val HERO_ARTWORK_WIDTH_FRACTION: Float = 0.72f

/**
 * The other half of the cap: the fraction of the height below the header the artwork may take.
 *
 * Width alone was the whole rule, and a rule about width alone is only ever right on a window
 * taller than it is wide. On anything else — a phone laid on its side, the inner display of a
 * folded one — 72 % of the width is a square the screen has no room for, and what gets crushed is
 * the half of the player that has buttons in it. Whichever of the two caps bites is the one whose
 * side had less to give.
 */
internal const val HERO_ARTWORK_HEIGHT_FRACTION: Float = 0.45f

private val SkipGlyphSize: Dp = 32.dp

/** Where the control strip's gradient has finished becoming opaque, as a fraction of its height. */
private const val CONTROL_STRIP_SOLID_AT = 0.35f

/** The mark that says the rate on the button is the show's rather than the app's. */
private val SHOW_SPEED_DOT: Dp = 6.dp

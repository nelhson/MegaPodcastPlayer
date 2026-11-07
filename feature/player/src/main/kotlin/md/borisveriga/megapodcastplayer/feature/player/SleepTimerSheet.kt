package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import md.borisveriga.megapodcastplayer.core.common.format.formatDuration
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.component.MenuChip
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.media.SleepTimerState

/**
 * The sleep timer, as a sheet.
 *
 * One control where there were two. The transport row used to carry a bell that rang at the end of
 * the episode — a good answer to "wake me when this finishes" and no answer at all to "stop in
 * twenty minutes", which is the version people ask for. Both are options here, and the bell's
 * behaviour is untouched: it is simply the one called *End of episode*.
 *
 * The length of time is set on a stepper rather than picked from a list. The list had sixteen
 * entries, a quarter of an hour apart, and still could not offer the twenty minutes someone wanted;
 * a stepper offers every length, five minutes at a time, in one row that needs no scrolling.
 *
 * *End of chapter* is offered only when the episode has chapters, because on an episode without
 * them it would be a row that either did nothing or silently meant something else. It is two
 * controls side by side: the chip means the chapter playing, which is what is nearly always meant,
 * and the menu beside it picks a later one.
 *
 * @param state what the timer is currently doing, so the running option is ticked.
 * @param chapterOptions the chapters that can be stopped after, the one playing first; empty when
 *   the episode has none. Computed by the caller, which is the only place that knows them.
 * @param onArmAfter arms a counted-down timer.
 * @param onArmEndOfEpisode arms the bell.
 * @param onArmEndOfChapter arms a stop at the end of the chapter with this index.
 * @param onCancel calls the whole thing off.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerState,
    chapterOptions: List<SleepChapterOption>,
    onArmAfter: (Long) -> Unit,
    onArmEndOfEpisode: () -> Unit,
    onArmEndOfChapter: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.sleep_title),
        subtitle = stringResource(R.string.sleep_description),
    ) {
        SleepTimerOptions(
            state = state,
            chapterOptions = chapterOptions,
            onArmAfter = onArmAfter,
            onArmEndOfEpisode = onArmEndOfEpisode,
            onArmEndOfChapter = onArmEndOfChapter,
            onCancel = onCancel,
            onDismiss = onDismiss,
        )
    }
}

/**
 * What is in the sheet, without the sheet.
 *
 * Apart from its container so that it can be previewed and have a screenshot taken: a modal sheet
 * draws in a window of its own, which neither can see into, and this is the layout that once broke.
 *
 * @param state what the timer is currently doing.
 * @param chapterOptions the chapters that can be stopped after; empty when the episode has none.
 * @param onArmAfter arms a counted-down timer.
 * @param onArmEndOfEpisode arms the bell.
 * @param onArmEndOfChapter arms a stop at the end of the chapter with this index.
 * @param onCancel calls the whole thing off.
 * @param onDismiss closes the sheet; every option calls it before it arms anything.
 * @param modifier layout modifier.
 */
@Composable
internal fun SleepTimerOptions(
    state: SleepTimerState,
    chapterOptions: List<SleepChapterOption>,
    onArmAfter: (Long) -> Unit,
    onArmEndOfEpisode: () -> Unit,
    onArmEndOfChapter: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal)
            .padding(bottom = MegaPodcastPlayerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
    ) {
        DurationPicker(
            remainingMs = state.remainingMs,
            onStart = { durationMs ->
                onDismiss()
                onArmAfter(durationMs)
            },
        )

        TimerChip(
            label = stringResource(R.string.sleep_end_of_episode),
            selected = state.isEndOfEpisode,
            onClick = {
                onDismiss()
                onArmEndOfEpisode()
            },
        )

        if (chapterOptions.isNotEmpty()) {
            ChapterRow(
                options = chapterOptions,
                armedIndex = state.endOfChapterIndex,
                onPick = { index ->
                    onDismiss()
                    onArmEndOfChapter(index)
                },
            )
        }

        if (state.isArmed) {
            TextButton(
                onClick = {
                    onDismiss()
                    onCancel()
                },
            ) {
                Text(text = stringResource(R.string.sleep_cancel))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Rounded.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.sleep_shake_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The length of time, as a stepper: the figure between a minus and a plus, and *Start* beside it.
 *
 * Five minutes a step, from five minutes up to half a day. The figure opens on half an hour, or —
 * while a countdown is running — on what is left of it, rounded up to the step, so that *Start*
 * then restarts the timer at about the same place and one tap on the plus is "five more minutes".
 * The caption above it says what the row does, and how long is left once a countdown is running.
 *
 * @param remainingMs what is left of a running countdown, or null.
 * @param onStart invoked with the length chosen, in milliseconds.
 */
@Composable
private fun DurationPicker(remainingMs: Long?, onStart: (Long) -> Unit) {
    val resources = LocalResources.current
    val remaining = formatDuration(resources, remainingMs)
    var minutes by rememberSaveable { mutableIntStateOf(startingMinutes(remainingMs)) }

    Column(verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs)) {
        Text(
            text = if (remaining != null) {
                stringResource(R.string.sleep_after_running, remaining)
            } else {
                stringResource(R.string.sleep_after)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
        ) {
            IconButton(
                onClick = { minutes -= SLEEP_STEP_MINUTES },
                enabled = minutes > SLEEP_STEP_MINUTES,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.sleep_less),
                )
            }
            Text(
                text = formatDuration(resources, minutes * MILLIS_PER_MINUTE).orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    // The figure takes the width the buttons leave, so it stays put as it grows
                    // from "5 min" to "1 h 30 min" and the buttons do not jump about under a thumb.
                    .weight(1f)
                    // Spoken as it changes, so a screen reader hears "35 min" after each tap on
                    // the plus without moving focus to find out where it got to.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(
                onClick = { minutes += SLEEP_STEP_MINUTES },
                enabled = minutes < SLEEP_MAX_MINUTES,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.sleep_more),
                )
            }
            FilledTonalButton(onClick = { onStart(minutes * MILLIS_PER_MINUTE) }) {
                Text(text = stringResource(R.string.sleep_start))
            }
        }
    }
}

/**
 * *End of chapter*, and beside it which chapter.
 *
 * The chip stops at the end of the chapter the menu names: the one already armed, or else the one
 * playing, so the common case is the one tap it always was. The menu takes the width that is left
 * and no more, and its label is held to a line, because a chapter title is as long as its publisher
 * liked.
 *
 * @param options the chapters on offer, the one playing first; not empty.
 * @param armedIndex the chapter the timer is already set to stop after, or null.
 * @param onPick invoked with the index of the chapter chosen.
 */
@Composable
private fun ChapterRow(options: List<SleepChapterOption>, armedIndex: Int?, onPick: (Int) -> Unit) {
    val armed = options.firstOrNull { it.index == armedIndex }
    val shown = armed ?: options.first()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimerChip(
            label = stringResource(R.string.sleep_end_of_chapter),
            selected = armedIndex != null,
            onClick = { onPick(shown.index) },
        )
        MenuChip(
            label = chapterCaption(shown),
            icon = Icons.Rounded.Bookmarks,
            options = options,
            selected = armed,
            onSelect = { option -> onPick(option.index) },
            menuDescription = stringResource(R.string.sleep_chapter_menu),
            optionLabel = { option -> chapterCaption(option) },
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * A chapter as the sheet writes it: its number, counted from one, and its title.
 *
 * @param option the chapter.
 * @return e.g. "3. Listener mail".
 */
@Composable
private fun chapterCaption(option: SleepChapterOption): String =
    stringResource(R.string.sleep_chapter_option, option.index + 1, option.title)

/**
 * One option that is a single tap.
 *
 * A filter chip rather than a list row: the options are short, and which one is running is the
 * whole state of this sheet — which is exactly what a filter chip is shaped to say.
 *
 * @param label the words.
 * @param selected whether this is the option currently running.
 * @param onClick arms it.
 */
@Composable
private fun TimerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        leadingIcon = if (selected) {
            { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
        } else {
            null
        },
    )
}

/**
 * Where the stepper opens.
 *
 * What is left of a running countdown, rounded up to a whole step and held within the stepper's
 * range; half an hour when nothing is running.
 *
 * @param remainingMs what is left of a running countdown, or null.
 * @return the minutes the stepper shows first.
 */
internal fun startingMinutes(remainingMs: Long?): Int {
    if (remainingMs == null || remainingMs <= 0L) return SLEEP_DEFAULT_MINUTES
    val steps = (remainingMs + SLEEP_STEP_MS - 1) / SLEEP_STEP_MS
    return (steps * SLEEP_STEP_MINUTES).toInt().coerceIn(SLEEP_STEP_MINUTES, SLEEP_MAX_MINUTES)
}

private const val MILLIS_PER_MINUTE = 60_000L

/** How far one tap on the stepper moves, and the least it will arm. */
private const val SLEEP_STEP_MINUTES = 5
private const val SLEEP_STEP_MS = SLEEP_STEP_MINUTES * MILLIS_PER_MINUTE

/** Where the stepper opens when nothing is running: the length most people reach for. */
private const val SLEEP_DEFAULT_MINUTES = 30

/** Half a day: past any episode, and the point at which a sleep timer is not what is wanted. */
private const val SLEEP_MAX_MINUTES = 12 * 60

@ThemePreviews
@Composable
internal fun SleepTimerOptionsPreview() {
    MegaPodcastPlayerTheme {
        SleepTimerOptions(
            state = SleepTimerState(endOfChapterIndex = 2),
            chapterOptions = listOf(
                SleepChapterOption(index = 1, title = "Intro"),
                SleepChapterOption(
                    index = 2,
                    title = "The interview, which the publisher gave a very long title indeed",
                ),
                SleepChapterOption(index = 3, title = "Listener mail"),
            ),
            onArmAfter = {},
            onArmEndOfEpisode = {},
            onArmEndOfChapter = {},
            onCancel = {},
            onDismiss = {},
        )
    }
}

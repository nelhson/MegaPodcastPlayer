package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
 * The lengths of time are a menu under one chip rather than a chip each. There are sixteen of them,
 * a quarter of an hour apart up to four hours, and the four there used to be already did not fit
 * across a phone: the last was squeezed until it wrapped and stood twice as tall as its neighbours.
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
        DurationMenu(
            remainingMs = state.remainingMs,
            onPick = { durationMs ->
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
 * The lengths of time, as a menu under one chip.
 *
 * @param remainingMs what is left of a running countdown, or null. It is both the chip's label and
 *   what decides which row is ticked.
 * @param onPick invoked with the length chosen, in milliseconds.
 */
@Composable
private fun DurationMenu(remainingMs: Long?, onPick: (Long) -> Unit) {
    val resources = LocalResources.current
    val remaining = formatDuration(resources, remainingMs)

    MenuChip(
        label = if (remaining != null) {
            stringResource(R.string.sleep_after_running, remaining)
        } else {
            stringResource(R.string.sleep_after)
        },
        icon = Icons.Rounded.Timer,
        options = SLEEP_MINUTES,
        // Ticked on what is *left* rather than on what was chosen, so the sheet shows what the
        // timer is doing rather than what it was asked to do. The window is a minute wide because
        // the countdown has already been running for however long the sheet took to open.
        selected = SLEEP_MINUTES.firstOrNull { remainingMs.isWithinAMinuteOf(it * MILLIS_PER_MINUTE) },
        onSelect = { minutes -> onPick(minutes * MILLIS_PER_MINUTE) },
        menuDescription = stringResource(R.string.sleep_after_menu),
        optionLabel = { minutes -> formatDuration(resources, minutes * MILLIS_PER_MINUTE).orEmpty() },
    )
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
 * Whether a countdown is close enough to [durationMs] to be the option that started it.
 *
 * @param durationMs the option's own length.
 * @return true when this many milliseconds are left of that option.
 */
private fun Long?.isWithinAMinuteOf(durationMs: Long): Boolean {
    val remaining = this ?: return false
    return remaining > durationMs - MILLIS_PER_MINUTE && remaining <= durationMs
}

/** The lengths on offer, in minutes: every quarter of an hour up to four hours. */
private val SLEEP_MINUTES = (15..240 step 15).toList()

private const val MILLIS_PER_MINUTE = 60_000L

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

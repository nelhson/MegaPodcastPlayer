package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.SleepTimerState

/**
 * The sleep timer, as a sheet.
 *
 * One control where there were two. The transport row used to carry a bell that rang at the end of
 * the episode — a good answer to "wake me when this finishes" and no answer at all to "stop in
 * twenty minutes", which is the version people ask for. Both are options here, and the bell's
 * behaviour is untouched: it is simply the one called *End of episode*.
 *
 * *End of chapter* is offered only when the episode has chapters, because on an episode without
 * them it would be a row that either did nothing or silently meant something else.
 *
 * @param state what the timer is currently doing, so the running option is ticked.
 * @param chapterRemainingMs how much of the current chapter is left, or null when the episode has
 *   no chapters. Computed by the caller, which is the only place that knows them.
 * @param onArmAfter arms a counted-down timer.
 * @param onArmEndOfEpisode arms the bell.
 * @param onCancel calls the whole thing off.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerState,
    chapterRemainingMs: Long?,
    onArmAfter: (Long) -> Unit,
    onArmEndOfEpisode: () -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal)
                .padding(bottom = MegaPodcastPlayerTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
            ) {
                SLEEP_MINUTES.forEach { minutes ->
                    val durationMs = minutes * MILLIS_PER_MINUTE
                    TimerChip(
                        label = pluralStringResource(R.plurals.sleep_minutes, minutes, minutes),
                        // Selected on what is *left* rather than on what was chosen, so the sheet
                        // shows what the timer is doing rather than what it was asked to do. The
                        // window is a minute wide because the countdown has already been running
                        // for however long the sheet took to open.
                        selected = state.remainingMs.isWithinAMinuteOf(durationMs),
                        onClick = {
                            onDismiss()
                            onArmAfter(durationMs)
                        },
                    )
                }
            }

            TimerChip(
                label = stringResource(R.string.sleep_end_of_episode),
                selected = state.isEndOfEpisode,
                onClick = {
                    onDismiss()
                    onArmEndOfEpisode()
                },
            )

            chapterRemainingMs?.let { remaining ->
                TimerChip(
                    label = stringResource(R.string.sleep_end_of_chapter),
                    selected = false,
                    onClick = {
                        onDismiss()
                        onArmAfter(remaining)
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
}

/**
 * One option.
 *
 * A filter chip rather than a list row: the options are short, there are six of them, and which one
 * is running is the whole state of this sheet — which is exactly what a filter chip is shaped to
 * say.
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

/** The four fixed lengths, in minutes. */
private val SLEEP_MINUTES = listOf(15, 30, 45, 60)

private const val MILLIS_PER_MINUTE = 60_000L

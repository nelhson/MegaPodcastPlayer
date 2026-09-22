package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

/**
 * The speed picker, as a sheet.
 *
 * It replaces a cycle button. Eight presets that wrapped from 3× back to 0.8× made the fastest
 * setting a trap — one tap past the end and a podcast was suddenly crawling — and nothing between
 * two presets was reachable at all, which is the one thing a speed control is for: 1.35× is a real
 * answer to a particular host, and the cycle could not say it.
 *
 * Three ways in, deliberately, because they are three different intentions. The presets are "put it
 * where I usually have it". The slider is "find the fastest this person is still intelligible at",
 * which is a search, not a choice. The minus and plus buttons are the same search for someone who
 * cannot hit a 0.05 target on a slider, and they are what makes this sheet usable with TalkBack or
 * a shaky hand.
 *
 * Dragging is heard, not just seen: [onPreview] applies the rate to the running player on every
 * change so the ear can do the choosing, and [onCommit] — on release, and on every tap — is the one
 * that writes the preference. Persisting each frame of a drag would put thirty disk writes behind
 * one gesture.
 *
 * A per-show override belongs here too (SHOW-6); it is not built yet, and this sheet is where it
 * will go rather than a second control somewhere else.
 *
 * @param speed the rate currently playing, which seeds the sheet.
 * @param onPreview applies a rate to the player without remembering it.
 * @param onCommit applies a rate and remembers it.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    speed: Float,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.speed_title),
        subtitle = stringResource(R.string.speed_description),
    ) {
        SpeedControls(speed = speed, onPreview = onPreview, onCommit = onCommit)
    }
}

/**
 * The sheet's contents, without the sheet.
 *
 * Its own composable so that a preview — and therefore a golden — can hold it: the three ways in
 * are a layout that has to survive 200 % text, and the default chip's marking is a thing only an
 * image can check.
 *
 * @param speed the rate currently playing, which seeds the controls.
 * @param onPreview applies a rate to the player without remembering it.
 * @param onCommit applies a rate and remembers it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeedControls(speed: Float, onPreview: (Float) -> Unit, onCommit: (Float) -> Unit) {
    // Seeded once rather than followed: while the sheet is open the finger is the source of truth,
    // and the player's own reported rate arrives a frame or two behind the drag that caused it.
    var draft by remember { mutableFloatStateOf(speed.coerceIn(PlaybackSettings.SPEED_RANGE)) }
    // Read outside the semantics block: that lambda runs outside composition, where a resource
    // lookup is not available.
    val sliderLabel = stringResource(R.string.speed_slider)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal)
            .padding(bottom = MegaPodcastPlayerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
    ) {
        NudgeRow(
            speed = draft,
            onNudge = { delta ->
                draft = (draft + delta).snapToStep()
                onCommit(draft)
            },
        )

        Slider(
            value = draft,
            onValueChange = { requested ->
                draft = requested.snapToStep()
                onPreview(draft)
            },
            // The preference is written once, when the finger leaves the track. What was heard
            // during the drag was already the real rate; this only makes it survive a restart.
            onValueChangeFinished = { onCommit(draft) },
            valueRange = PlaybackSettings.SPEED_RANGE,
            // Continuous rather than stepped: fifty tick marks across a phone's width is a
            // pattern, not a scale. The snapping happens in the handler instead.
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = sliderLabel
                    stateDescription = formatSpeed(draft)
                },
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs),
        ) {
            PlaybackSettings.SPEED_STEPS.forEach { preset ->
                PresetChip(
                    speed = preset,
                    selected = draft.isSame(preset),
                    isDefault = preset.isSame(PlaybackSettings.DEFAULT_SPEED),
                    onClick = {
                        draft = preset
                        onCommit(preset)
                    },
                )
            }
        }
    }
}

/**
 * Minus, the current rate, plus.
 *
 * The number is the largest thing on the sheet because it is the answer; the buttons either side
 * are the fine adjustment the slider cannot give a thumb. Both are labelled, because "minus" and
 * "plus" spoken without their subject could be any pair of buttons in the app.
 *
 * @param speed the rate to show.
 * @param onNudge invoked with the signed change to apply.
 */
@Composable
private fun NudgeRow(speed: Float, onNudge: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            MegaPodcastPlayerTheme.spacing.md,
            Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { onNudge(-SPEED_NUDGE) },
            enabled = speed > PlaybackSettings.SPEED_RANGE.start,
        ) {
            Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = stringResource(R.string.speed_slower),
            )
        }

        Text(
            text = formatSpeed(speed),
            // The numeric role, scaled up: the digits change under a moving thumb, and without
            // tabular figures the whole row would shuffle sideways on every hundredth.
            style = MegaPodcastPlayerTheme.type.numericLarge.copy(
                fontSize = SPEED_READOUT_SIZE,
                lineHeight = SPEED_READOUT_LINE_HEIGHT,
            ),
            textAlign = TextAlign.Center,
            // Wide enough that "0.85x" and "3x" leave the buttons in the same place.
            modifier = Modifier.widthIn(min = SPEED_READOUT_MIN_WIDTH),
        )

        FilledTonalIconButton(
            onClick = { onNudge(SPEED_NUDGE) },
            enabled = speed < PlaybackSettings.SPEED_RANGE.endInclusive,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.speed_faster),
            )
        }
    }
}

/**
 * One preset.
 *
 * A filter chip, like the sleep timer's options, for the same reason: which one is on is the whole
 * state being shown. A rate reached with the slider ticks whichever preset it landed on, so the
 * chips stay a description of the current speed rather than a memory of the last tap.
 *
 * The default rate is the one chip marked as such. It used to be a "Back to normal speed" button
 * under the row, which said the same thing as the 1× chip beside it and appeared and disappeared as
 * the slider crossed 1×. The mark says it once, in both states, without moving anything.
 *
 * It is said three times over because each says it to someone the others miss. The tertiary palette,
 * which nothing else on this sheet uses, reads as "a different kind of thing" — but a pale teal and
 * the pale olive of a *selected* chip are the same lightness, so on a greyscale screen or to a
 * red-green-deficient eye that mark is not there at all. Hence the glyph, which is a shape:
 * "restore", the verb the removed button had. And hence the words for TalkBack, which sees neither.
 *
 * @param speed the preset's rate.
 * @param selected whether it is the rate currently set.
 * @param isDefault whether it is the rate the app ships with.
 * @param onClick applies it.
 */
@Composable
private fun PresetChip(speed: Float, selected: Boolean, isDefault: Boolean, onClick: () -> Unit) {
    // Read outside the semantics block: that lambda runs outside composition. Only the chip that
    // uses it looks it up.
    val defaultLabel = if (isDefault) {
        stringResource(R.string.speed_default, formatSpeed(speed))
    } else {
        null
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = formatSpeed(speed)) },
        // Selected wins the slot when both apply: which rate is on is the sheet's whole subject,
        // and the default chip keeps a glyph either way, so its width does not change under a tap.
        leadingIcon = when {
            selected -> {
                { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
            }

            isDefault -> {
                { Icon(imageVector = Icons.Rounded.Restore, contentDescription = null) }
            }

            else -> null
        },
        // Its own selected state still has to be visibly the selected one, so that is the full
        // tertiary rather than the container.
        colors = if (isDefault) {
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                iconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onTertiary,
            )
        } else {
            FilterChipDefaults.filterChipColors()
        },
        // The description replaces the label rather than joining it, which is why the string
        // carries the rate as well. "Selected" is a state, not a name, so the chip's own
        // selectable semantics still announce it.
        modifier = if (defaultLabel != null) {
            Modifier.semantics { contentDescription = defaultLabel }
        } else {
            Modifier
        },
    )
}

/**
 * Rounds a rate to the nearest step the sheet offers and keeps it in range.
 *
 * A slider hands back whatever fraction of the track the thumb sits on; a speed of `1.4732841x`
 * would be shown as `1.47x`, stored, and then never selected again by any chip. Everything this
 * sheet produces is a multiple of [SPEED_NUDGE].
 *
 * @return the nearest allowed rate.
 */
private fun Float.snapToStep(): Float =
    ((this / SPEED_NUDGE).roundToInt() * SPEED_NUDGE).coerceIn(PlaybackSettings.SPEED_RANGE)

/**
 * Whether two rates are the same setting.
 *
 * Rates arrive as rounded preference values and as slider fractions, so `1.5f` and `1.4999999f` are
 * the same intention and have to compare equal or a chip would never tick.
 *
 * @param other the rate to compare with.
 * @return true when they are within half a step of each other.
 */
private fun Float.isSame(other: Float): Boolean = abs(this - other) < SPEED_NUDGE / 2

/** The smallest change the sheet can make: fine enough to hunt with, coarse enough to say aloud. */
private const val SPEED_NUDGE = 0.05f

private val SPEED_READOUT_SIZE = 32.sp
private val SPEED_READOUT_LINE_HEIGHT = 40.sp
private val SPEED_READOUT_MIN_WIDTH = 96.dp

/**
 * The controls at 2×, which is the state worth an image.
 *
 * Not at 1×: with the default rate selected, the default chip and the selected chip are the same
 * chip and nothing is being distinguished. At 2× they are two chips side by side, which is exactly
 * the confusion the glyph exists to prevent, and the golden is where a future change to either
 * marking would show up.
 */
@ThemePreviews
@Composable
internal fun SpeedControlsPreview() {
    MegaPodcastPlayerTheme {
        SpeedControls(speed = 2f, onPreview = {}, onCommit = {})
    }
}

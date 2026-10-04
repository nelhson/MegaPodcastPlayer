package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.PlayerMode

/**
 * The switch between the player's two faces: one control of two segments, *Audio* and *Video*.
 *
 * The same control in the same corner of both faces — the sheet's header and the video screen's
 * top bar — so that the way from one to the other is the way back. It replaced a screen glyph on
 * the sheet and a pair of headphones on the video screen, which were two icons for one idea and
 * said nothing about which face the user was already in. It is drawn only for an episode that has
 * a picture; the caller decides that, since an episode without one has a single face and nothing
 * to switch.
 *
 * The segment for the face being shown is the selected one and does nothing when tapped: asking
 * for what is already there is not a change. The other one reports the switch, and is spoken as
 * the change it makes — *Switch to video* — rather than as the bare noun it is drawn with.
 *
 * It carries its own ground. Both of its homes put it over imagery nobody chose — the blurred
 * cover behind the sheet, the picture itself in landscape — so a control that borrowed its
 * contrast from what is behind it would have none to rely on.
 *
 * @param selected the face the control is drawn on.
 * @param onSwitch asks for the other face. Never called for [selected].
 * @param modifier layout modifier.
 * @param enabled whether the control takes taps; false while its surface is part-way in.
 */
@Composable
fun ModeSwitch(
    selected: PlayerMode,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(MegaPodcastPlayerTheme.shapes.pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(
            label = stringResource(R.string.player_mode_audio),
            switchLabel = stringResource(R.string.video_listen),
            selected = selected == PlayerMode.AUDIO,
            enabled = enabled,
            onSwitch = onSwitch,
        )
        Segment(
            label = stringResource(R.string.player_mode_video),
            switchLabel = stringResource(R.string.player_switch_to_video),
            selected = selected == PlayerMode.VIDEO,
            enabled = enabled,
            onSwitch = onSwitch,
        )
    }
}

/**
 * One of the two segments.
 *
 * The tap target is the full touch-target height with the drawn pill inset in it, so the control
 * reads as a slim switch and still gives a thumb the room a button does.
 *
 * @param label the face's name, as drawn.
 * @param switchLabel what a tap does, as spoken.
 * @param selected whether this is the face being shown.
 * @param enabled whether the segment takes taps.
 * @param onSwitch asks for this face; not called when it is already [selected].
 */
@Composable
private fun Segment(
    label: String,
    switchLabel: String,
    selected: Boolean,
    enabled: Boolean,
    onSwitch: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clickable(
                enabled = enabled,
                // The selected segment has nothing to announce: a tap on it changes nothing.
                onClickLabel = if (selected) null else switchLabel,
                role = Role.RadioButton,
                onClick = { if (!selected) onSwitch() },
            )
            .semantics { this.selected = selected }
            .padding(SegmentInset),
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = SegmentMinSize)
                .widthIn(min = SegmentMinSize)
                .clip(MegaPodcastPlayerTheme.shapes.pill)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                )
                .padding(horizontal = MegaPodcastPlayerTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

/** The gap between a segment's tap target and the pill drawn in it. */
private val SegmentInset: Dp = 4.dp

/** The drawn pill's least side; with [SegmentInset] around it, one touch target. */
private val SegmentMinSize: Dp = 40.dp

/** The control on each face, at three font scales: the labels are the whole of its width. */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun ModeSwitchPreview() {
    MegaPodcastPlayerTheme {
        // Stacked: side by side, two of them are wider than a phone at 200 % text.
        Column(verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm)) {
            ModeSwitch(selected = PlayerMode.AUDIO, onSwitch = {})
            ModeSwitch(selected = PlayerMode.VIDEO, onSwitch = {})
        }
    }
}

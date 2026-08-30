package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import md.borisveriga.bpodcat.core.designsystem.R
import md.borisveriga.bpodcat.core.designsystem.shape.BPodcatPolygons
import md.borisveriga.bpodcat.core.designsystem.shape.MorphShape
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.Motion
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews

/**
 * The play/pause control.
 *
 * The container is a true polygon morph, not an animated corner radius: circle while paused,
 * squircle while playing, interpolating continuously through the press. That is the Expressive
 * gesture material3 1.4.0 keeps internal, rebuilt on stable `graphics-shapes`. Pressing squashes
 * the shape a little further, so the control feels physical rather than merely animated.
 *
 * Buffering is drawn as the same shape morphing on its own with the glyph faded out, which keeps
 * the control the same size and in the same place — a spinner that replaces the button makes the
 * layout jump exactly when the user is trying to hit it again.
 *
 * @param playing whether playback is currently running.
 * @param onToggle invoked with the requested new state.
 * @param modifier layout modifier.
 * @param size which rung of the scale to draw.
 * @param buffering whether playback is preparing; the control stays interactive.
 * @param containerColor the fill. Defaults to the citron primary.
 * @param contentColor the glyph colour.
 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: PlayPauseSize = PlayPauseSize.Medium,
    buffering: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val morph = remember { Morph(BPodcatPolygons.Circle, BPodcatPolygons.Squircle) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // One driver for the shape: paused sits at the circle end, playing at the squircle end, and a
    // press pushes a little past wherever it currently is.
    val target = when {
        pressed -> if (playing) 1f else PRESSED_FROM_CIRCLE
        playing -> 1f
        else -> 0f
    }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = Motion.bouncy(),
        label = "playPauseMorph",
    )
    val glyphAlpha by animateFloatAsState(
        targetValue = if (buffering) BUFFERING_GLYPH_ALPHA else 1f,
        animationSpec = Motion.fade(),
        label = "glyphAlpha",
    )
    val fill by animateColorAsState(
        targetValue = containerColor,
        animationSpec = Motion.fade(),
        label = "fill",
    )

    val description = stringResource(
        when {
            buffering -> R.string.designsystem_buffering
            playing -> R.string.designsystem_pause
            else -> R.string.designsystem_play
        },
    )

    Box(
        modifier = modifier
            .size(size.container)
            .background(color = fill, shape = MorphShape(morph, progress))
            .toggleable(
                value = playing,
                onValueChange = onToggle,
                interactionSource = interactionSource,
                // No ripple: the shape morph *is* the press feedback, and a ripple clipped to a
                // morphing outline flickers as the outline changes.
                indication = null,
                role = Role.Button,
            )
            // The glyph swaps between two icons, so describing the control here keeps TalkBack
            // from announcing "play" one frame and "pause" the next.
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = contentColor.copy(alpha = glyphAlpha),
            modifier = Modifier.size(size.glyph),
        )
    }
}

/** How far a press pushes the shape when the control is at rest (paused). */
private const val PRESSED_FROM_CIRCLE = 0.55f

/** The glyph dims rather than disappears while buffering, so the control still reads as a button. */
private const val BUFFERING_GLYPH_ALPHA = 0.35f

@ThemePreviews
@Composable
private fun PlayPauseButtonPreview() {
    BPodcatTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPauseButton(playing = false, onToggle = {}, size = PlayPauseSize.Small)
            PlayPauseButton(playing = true, onToggle = {}, size = PlayPauseSize.Medium)
            PlayPauseButton(playing = true, onToggle = {}, size = PlayPauseSize.Hero, buffering = true)
        }
    }
}

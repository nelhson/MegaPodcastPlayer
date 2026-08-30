package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.Motion

/**
 * A floating bar of actions that apply to whatever is currently selected.
 *
 * Bulk actions used to live in the top app bar, permanently, whether or not there was anything to
 * act on — and a "delete everything" button one mis-tap from the title is a poor place for it. This
 * appears only once a selection exists, next to the thumb that made it, and takes the actions with
 * it when the selection is cleared.
 *
 * Hand-built rather than taken from Material: `FloatingToolbar` is Material 3 Expressive, and in
 * material3 1.4.0 it does not exist in the public API.
 *
 * @param visible whether anything is selected; drives the slide-in.
 * @param label what the selection is, e.g. "3 selected". Announced politely when it changes, so a
 *   TalkBack user hears the count without hunting for it.
 * @param modifier layout modifier; the caller places the bar, usually bottom-centre in a [
 *   androidx.compose.foundation.layout.Box].
 * @param actions the buttons, in the order they should be reached.
 */
@Composable
fun SelectionToolbar(
    visible: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        // Rises from the edge it is anchored to and sinks back into it, so the bar reads as part
        // of the gesture that summoned it rather than as a dialog that arrived.
        enter = slideInVertically(animationSpec = Motion.bouncy()) { height -> height } + fadeIn(),
        exit = slideOutVertically(animationSpec = Motion.smooth()) { height -> height } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = BPodcatTheme.shapes.pill,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            tonalElevation = BPodcatTheme.elevation.level3,
            shadowElevation = BPodcatTheme.elevation.level3,
        ) {
            Row(
                modifier = Modifier.padding(
                    start = BPodcatTheme.spacing.lg,
                    end = BPodcatTheme.spacing.sm,
                    top = BPodcatTheme.spacing.xs,
                    bottom = BPodcatTheme.spacing.xs,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                actions()
            }
        }
    }
}

@Preview
@Composable
private fun SelectionToolbarPreview() {
    BPodcatTheme {
        SelectionToolbar(visible = true, label = "3 selected") {
            Text(text = "Remove")
        }
    }
}

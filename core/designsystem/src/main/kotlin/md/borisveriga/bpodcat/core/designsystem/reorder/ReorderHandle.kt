package md.borisveriga.bpodcat.core.designsystem.reorder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme

/**
 * The grip a reorder drag starts from.
 *
 * A dedicated handle rather than a long press on the row: the rows this sits in are already buttons
 * — playing an episode, opening a show — and a long press that both scrolls and reorders is the
 * classic way to make a list feel broken. It also gives the gesture a visible target, which a long
 * press never has. Grid tiles have no room for one and use
 * [reorderableLongPressDrag][Modifier.reorderableLongPressDrag] instead.
 *
 * @param modifier layout modifier; the call site attaches the gesture through
 *   [reorderableHandle][Modifier.reorderableHandle].
 */
@Composable
fun ReorderHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(BPodcatTheme.spacing.minTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            // The gesture is published as a custom action on the row instead; announcing the
            // handle would offer a screen reader a control it cannot operate.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

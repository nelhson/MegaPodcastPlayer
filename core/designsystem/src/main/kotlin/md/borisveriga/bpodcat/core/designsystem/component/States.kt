package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import md.borisveriga.bpodcat.core.designsystem.R

/**
 * Centred progress indicator for a screen that has nothing to show yet.
 *
 * @param modifier layout modifier.
 * @param contentDescription announced by TalkBack while the screen is busy.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.designsystem_loading),
) {
    Box(
        // The spinner carries no text of its own, so the state is described on the container.
        modifier = modifier
            .fillMaxSize()
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Full-screen empty or error state with an optional action.
 *
 * @param icon glyph illustrating the state; decorative, described by [title].
 * @param title short headline, e.g. "No podcasts yet".
 * @param description one or two sentences explaining what to do next.
 * @param modifier layout modifier.
 * @param actionLabel label for the optional button.
 * @param onAction invoked when the button is pressed; the button is hidden when null.
 */
@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(top = 12.dp)) {
                Text(text = actionLabel)
            }
        }
    }
}

package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme

/**
 * The app's bottom sheet: one container, one header, one set of edges.
 *
 * Everything that is *about* something — an episode's details, a show's settings, the speed
 * picker — arrives from the bottom rather than as a new screen, because it is a detour rather than
 * a destination: nothing about it belongs on the back stack, and the thing it is about should stay
 * visible behind it.
 *
 * The header is part of the container rather than each caller's own, which is the whole reason this
 * exists. Four screens were about to grow a sheet apiece, and the last time that happened — with
 * the app bar — the result was three spellings of a back arrow and two title truncations.
 *
 * The navigation-bar inset is applied here too. A sheet is the one surface that reaches the bottom
 * edge of the screen, so it is the one surface where forgetting the gesture bar puts a button under
 * the user's thumb-swipe.
 *
 * @param onDismiss invoked when the sheet is dragged down, tapped outside, or dismissed by the
 *   back gesture. The caller owns whether the sheet exists at all.
 * @param modifier layout modifier.
 * @param title the sheet's name, drawn as a heading; null draws no header at all, for a sheet whose
 *   content names itself.
 * @param subtitle a quieter second line under [title].
 * @param sheetState hoisted so a caller can animate the sheet closed before acting on a tap.
 * @param content the sheet's body, in a column that already carries the side padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MegaPodcastPlayerBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (title != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                            end = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                            bottom = MegaPodcastPlayerTheme.spacing.md,
                        ),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // A sheet is a screen's worth of content with no app bar; without this a
                        // screen reader has no landmark to jump to and has to walk in from the top.
                        modifier = Modifier.semantics { heading() },
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.xxs),
                        )
                    }
                }
            }

            content()
        }
    }
}

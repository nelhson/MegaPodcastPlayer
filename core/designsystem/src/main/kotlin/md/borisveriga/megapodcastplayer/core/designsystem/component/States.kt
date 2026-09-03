package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.shape.MegaPodcastPlayerPolygons
import md.borisveriga.megapodcastplayer.core.designsystem.shape.MorphShape
import md.borisveriga.megapodcastplayer.core.designsystem.shape.RoundedPolygonShape
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

/**
 * The screen has nothing to show yet.
 *
 * A morphing polygon rather than a spinning arc. Material 3 Expressive replaced the indeterminate
 * circular indicator with exactly this for waits under a few seconds, and it does the job better:
 * a shape that is continuously changing reads as "working" without implying a measurable
 * proportion the way a partial ring does.
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
        // The indicator carries no text of its own, so the state is described on the container.
        // `liveRegion` makes TalkBack announce the transition into and out of loading rather than
        // leaving the user to discover that the screen changed.
        modifier = modifier
            .fillMaxSize()
            .semantics {
                this.contentDescription = contentDescription
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        MorphingIndicator()
    }
}

/**
 * The animated shape used by [LoadingState].
 *
 * Two animations run against each other: the polygon morphs cookie to clover and back, while the
 * whole thing rotates. Either alone looks mechanical; together they read as organic.
 *
 * @param modifier layout modifier.
 * @param size the diameter of the indicator.
 */
@Composable
fun MorphingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = INDICATOR_SIZE,
) {
    // The Morph itself does the expensive polygon matching, so it is built once and reused; only
    // the cheap MorphShape wrapper is allocated per frame.
    val morph = remember { Morph(MegaPodcastPlayerPolygons.Cookie, MegaPodcastPlayerPolygons.Clover) }
    val transition = rememberInfiniteTransition(label = "loading")

    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MORPH_CYCLE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "morph",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPIN_CYCLE_MS),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    Box(
        modifier = modifier
            .size(size)
            .rotate(rotation)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = MorphShape(morph, progress),
            ),
    )
}

/**
 * Full-screen empty or error state with an optional action.
 *
 * The glyph sits on a large morph-shaped ground rather than floating alone: an empty screen is the
 * one place the design system has room to be generous, and a bare 48dp icon in the middle of a
 * blank page reads as a failure even when the message is cheerful.
 *
 * @param icon glyph illustrating the state; decorative, described by [title].
 * @param title short headline, e.g. "No podcasts yet".
 * @param description one or two sentences explaining what to do next.
 * @param modifier layout modifier.
 * @param actionLabel label for the optional button.
 * @param onAction invoked when the button is pressed; the button is hidden when null.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val ground = remember { RoundedPolygonShape(MegaPodcastPlayerPolygons.Cookie) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(MegaPodcastPlayerTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(GROUND_SIZE)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, ground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(GROUND_GLYPH_SIZE),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.xl),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.xl),
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

private val INDICATOR_SIZE = 44.dp
private val GROUND_SIZE = 112.dp
private val GROUND_GLYPH_SIZE = 44.dp
private const val MORPH_CYCLE_MS = 900
private const val SPIN_CYCLE_MS = 4200

@ThemePreviews
@Composable
private fun LoadingStatePreview() {
    MegaPodcastPlayerTheme {
        Box(modifier = Modifier.size(200.dp)) {
            LoadingState()
        }
    }
}

@ThemePreviews
@Composable
private fun EmptyStatePreview() {
    MegaPodcastPlayerTheme {
        EmptyState(
            icon = Icons.Rounded.Podcasts,
            title = "Nothing here yet",
            description = "Add a show and its newest episodes will appear on this screen.",
            actionLabel = "Find a show",
            onAction = {},
        )
    }
}

package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme

/**
 * The app bar every screen uses.
 *
 * Six screens each built their own [TopAppBar] before this existed, which is how the app ended up
 * with three different ways of spelling a back arrow and two different title truncations. The
 * navigation slot is a nullable lambda rather than a composable slot on purpose: a back arrow is
 * either there or it is not, and every screen that has one draws the same glyph with the same
 * label.
 *
 * @param title the screen's name.
 * @param modifier layout modifier.
 * @param onBack invoked by the back arrow; no arrow is drawn when null, which is what a top-level
 *   destination wants.
 * @param backDescription what TalkBack announces for the back arrow. Defaulted rather than
 *   required, because "Back" is the right answer on every screen that does not say otherwise.
 * @param scrollBehavior connects the bar to the list under it, so it can tint as content scrolls.
 * @param actions trailing icon buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MegaPodcastPlayerTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String = stringResource(R.string.designsystem_back),
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = { BackAction(onBack = onBack, contentDescription = backDescription) },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The app bar for a screen whose name is part of its identity.
 *
 * Collapses into [MegaPodcastPlayerTopAppBar]'s proportions as the list under it scrolls: expanded, Material
 * sets the title in `headlineMedium`, which is Bricolage, so the screen opens on the brand's face
 * and gives it up to Inter's `titleLarge` once the content matters more than the label. Both styles
 * come from the theme's own typography rather than being restated here.
 *
 * @param title the screen's name.
 * @param scrollBehavior required rather than optional: a large bar that never collapses is a large
 *   bar that permanently spends a third of the screen on one word.
 * @param modifier layout modifier.
 * @param onBack invoked by the back arrow; no arrow is drawn when null.
 * @param backDescription what TalkBack announces for the back arrow.
 * @param actions trailing icon buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MegaPodcastPlayerLargeTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String = stringResource(R.string.designsystem_back),
    actions: @Composable RowScope.() -> Unit = {},
) {
    LargeTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = { BackAction(onBack = onBack, contentDescription = backDescription) },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The back arrow, or nothing at all.
 *
 * @param onBack the handler; null draws nothing, so the title starts at the screen edge.
 * @param contentDescription what TalkBack announces.
 */
@Composable
private fun BackAction(onBack: (() -> Unit)?, contentDescription: String) {
    if (onBack == null) return
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = contentDescription,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun MegaPodcastPlayerTopAppBarPreview() {
    MegaPodcastPlayerTheme {
        MegaPodcastPlayerTopAppBar(title = "Settings", onBack = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun MegaPodcastPlayerLargeTopAppBarPreview() {
    MegaPodcastPlayerTheme {
        MegaPodcastPlayerLargeTopAppBar(
            title = "Library",
            scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
        )
    }
}

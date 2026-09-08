package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

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
 * @param subtitle a second, quieter line under the title, for a fact about the screen's contents
 *   rather than a second name for it — "3 h 12 min left" over a queue. Null draws one line, which
 *   is what every screen but the queue wants.
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
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String = stringResource(R.string.designsystem_back),
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        modifier = modifier,
        navigationIcon = { BackAction(onBack = onBack, contentDescription = backDescription) },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

// There was a `MegaPodcastPlayerLargeTopAppBar` here, a collapsing bar for "a screen whose name is
// part of its identity". Listen and Moments were its two callers and both gave it up when NAV-7
// settled on one bar for all five top-level destinations: a collapsing bar on two of five reads as
// an accident rather than as emphasis, and a list you came to scroll should not open with a third
// of the screen naming the tab you just tapped. It is removed rather than kept for the detail
// screen that might one day earn it — the design system describes the app, and Material's
// `LargeTopAppBar` is one import away on the day a screen does.

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
@ThemePreviews
@FontScalePreviews
@Composable
internal fun MegaPodcastPlayerTopAppBarPreview() {
    MegaPodcastPlayerTheme {
        MegaPodcastPlayerTopAppBar(title = "Settings", onBack = {})
    }
}

package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import md.borisveriga.megapodcastplayer.core.designsystem.R

/**
 * The two things every top-level destination has in common besides its bar.
 *
 * Both exist because a tab is not a screen in isolation — it is one of five, and the five have to
 * behave the same. Settings has to be one tap from any of them, and re-tapping the tab you are
 * standing on has to do what re-tapping a tab does everywhere else.
 */

/**
 * The gear, on every top-level bar.
 *
 * It used to be on the library's alone, which made settings a tab switch plus a tap from four of
 * the five screens. An overflow was the alternative and is worse: a menu holding one item on four
 * screens out of five is a container invented to hide the single thing inside it (NAV-5, D-7).
 *
 * @param onClick opens settings.
 * @param modifier layout modifier.
 */
@Composable
fun SettingsAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.designsystem_settings),
        )
    }
}

/**
 * Puts a list back at the top when its tab is tapped again.
 *
 * The signal is a *count* rather than a flag, because the same request can be made twice and a
 * boolean that was already true the second time would do nothing. The app increments it; a screen
 * reacts to it changing.
 *
 * The remembered first value is what stops this firing on arrival. Every `LaunchedEffect` runs once
 * when it enters the composition, and without the guard a tab returned to — with a scroll position
 * the navigation library had carefully saved — would be scrolled back to the top for no reason.
 * What the screen is reacting to is the signal *changing while it is on screen*, which is precisely
 * a re-tap.
 *
 * @param signal the app's re-tap count for this destination.
 * @param state the list to scroll.
 */
@Composable
fun ScrollToTopEffect(signal: Int, state: LazyListState) {
    val arrivedAt = remember { signal }
    LaunchedEffect(signal) {
        if (signal != arrivedAt) state.animateScrollToItem(0)
    }
}

/**
 * The same, for a grid.
 *
 * Overloads rather than one function taking a common type, because the three scrollable states this
 * app's top-level screens use share no interface that carries a scroll-to-the-beginning — and the
 * alternative, a lambda parameter, would make every call site restate the one line these exist to
 * hold.
 *
 * @param signal the app's re-tap count for this destination.
 * @param state the grid to scroll.
 */
@Composable
fun ScrollToTopEffect(signal: Int, state: LazyGridState) {
    val arrivedAt = remember { signal }
    LaunchedEffect(signal) {
        if (signal != arrivedAt) state.animateScrollToItem(0)
    }
}

/**
 * The same, for a column that scrolls as one piece rather than as a list of items.
 *
 * Listen is the one screen of the five shaped that way: three shelves that scroll together, because
 * the shelves are the content and there is no fourth.
 *
 * @param signal the app's re-tap count for this destination.
 * @param state the scroll position to reset.
 */
@Composable
fun ScrollToTopEffect(signal: Int, state: ScrollState) {
    val arrivedAt = remember { signal }
    LaunchedEffect(signal) {
        if (signal != arrivedAt) state.animateScrollTo(0)
    }
}

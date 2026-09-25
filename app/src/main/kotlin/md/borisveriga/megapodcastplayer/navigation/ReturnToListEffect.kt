package md.borisveriga.megapodcastplayer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * What re-tapping the Library tab means while a show fills the screen: go back to the library.
 *
 * A show opened from the library is not a destination of the app's graph but a pane inside the
 * Library tab, so the shell sees a tap on Library as a *re*-tap and raises the scroll-to-top signal
 * (NAV-4) — which on a folded phone scrolled a list nobody could see, and left the show in front.
 * Everywhere else a re-tap from something pushed over a tab returns to the tab, and this makes the
 * pane agree.
 *
 * Only while the list is hidden. Opened out, the list is already beside the show, the re-tap has
 * a list to scroll, and closing the show it is standing next to would answer a question nobody
 * asked. Returning shows the list where it was left; the *next* re-tap is the one that scrolls it,
 * exactly as it would for any other screen returned to.
 *
 * The remembered first value stops this firing on arrival, for the reason `ScrollToTopEffect`
 * gives: what this reacts to is the signal changing while the tab is on screen.
 *
 * @param signal the app's re-tap count for the Library tab.
 * @param isListPaneVisible whether the library list is on screen.
 * @param onReturnToList closes the show and brings the list back.
 */
@Composable
internal fun ReturnToListOnReTapEffect(
    signal: Int,
    isListPaneVisible: Boolean,
    onReturnToList: suspend () -> Unit,
) {
    val arrivedAt = remember { signal }
    // Read when the signal changes rather than keyed on: the pane appearing or disappearing is not
    // a re-tap, and must not by itself close anything.
    val listVisible by rememberUpdatedState(isListPaneVisible)
    val returnToList by rememberUpdatedState(onReturnToList)
    LaunchedEffect(signal) {
        if (signal != arrivedAt && !listVisible) returnToList()
    }
}

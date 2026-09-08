package md.borisveriga.megapodcastplayer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.createGraph
import androidx.navigation.toRoute

/**
 * The four things the library's detail pane does to its own small graph.
 *
 * That graph is a back stack of exactly two shapes — [Route.NoShowSelected] at the bottom and at
 * most one [Route.PodcastDetail] above it — and keeping it that shape is most of the logic here.
 * It lives beside [navigateToTopLevel] rather than inside `LibraryListDetail` for the same reason
 * that one does: a back stack rule is the kind of thing that is wrong in a way no screenshot shows,
 * so it is written where a test can drive it with no composition around it.
 *
 * The fourth thing, [rememberDetailPaneGraph], is about *when* that stack exists rather than what
 * shape it is, and it is here for the same reason.
 */

/**
 * Builds the detail pane's graph and hands it to this controller, on screen or not.
 *
 * A `NavHost` sets its own controller's graph, and that is exactly what cannot be relied on here.
 * `AnimatedPane` draws a hidden pane through `AnimatedVisibility`, which does not compose what it
 * is not showing — so on a folded phone, where the detail pane is `Hidden` until a row is tapped,
 * the pane's `NavHost` has never run at the moment of the tap. [openShowInDetailPane] then reached
 * a controller with no graph at all and the app died on the tap with *"You must call setGraph()
 * before calling getGraph()"*: the library was unusable on the screen the phone is in most of the
 * time.
 *
 * Setting the graph from here, where both panes are, gives the pane a back stack from the first
 * frame. That fixes the crash and it is also the only version that looks right: the pane slides in
 * already showing the show that was asked for, rather than composing on the placeholder and
 * cross-fading to the show a frame later.
 *
 * Two things about the order. The view model store has to be set first, because
 * `setViewModelStore` refuses to run once the back stack is non-empty and setting the graph is what
 * fills it; it is the same store the pane's `NavHost` would install, since nothing between here and
 * the pane provides a new [LocalViewModelStoreOwner], and the later call from `NavHost` finds one
 * already set and returns. And the caller has to hand this exact [NavGraph] back to that `NavHost`,
 * because setting an *equal* graph refreshes the destinations in place while setting a different
 * one pops everything off first — which is the difference between the pane keeping the show it was
 * opened on and losing it the instant it becomes visible.
 *
 * @param builder the pane's destinations, written exactly as they would be inside `NavHost`.
 * @return the graph to pass to that `NavHost`.
 */
@Composable
internal fun NavHostController.rememberDetailPaneGraph(
    builder: NavGraphBuilder.() -> Unit,
): NavGraph {
    val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "The library's detail pane needs a ViewModelStoreOwner to scope its show to."
    }
    setViewModelStore(viewModelStoreOwner.viewModelStore)

    val detailGraph = remember(this, builder) {
        createGraph(startDestination = Route.NoShowSelected, builder = builder)
    }
    // Reassigned on every composition, as `NavHost` does with the graph it builds: a rebuilt graph
    // is equal to the one already set, and setting it swaps in destinations that close over the
    // current pane width so the screens already on the stack see it.
    graph = detailGraph
    return detailGraph
}

/**
 * Opens a show in the detail pane, replacing whatever it was showing.
 *
 * `popUpTo` rather than a plain push, because the pane shows one show at a time. Without it, three
 * shows glanced at from the library would leave three entries, and the system back gesture would
 * walk back through them one at a time instead of leaving the pane — the user tapped three rows of
 * one list, and to them that is one place visited three times, not three places.
 *
 * The early return is the same promise [navigateToTopLevel] makes about a tab the user is already
 * standing on, and it is needed here for the same reason it is needed there: `launchSingleTop` does
 * not cover it. That flag reuses an entry only when the destination is already on top, and the
 * `popUpTo` above has just taken it off — so without the guard, tapping the highlighted row would
 * throw away the show screen beside it and build a fresh one, losing its scroll position, its open
 * sheet and its view model to a tap that asked for nothing.
 *
 * @param podcastId the show to open.
 */
internal fun NavController.openShowInDetailPane(podcastId: String) {
    if (currentBackStackEntry.openPodcastId() == podcastId) return

    navigate(Route.PodcastDetail(podcastId)) {
        popUpTo<Route.NoShowSelected>()
    }
}

/**
 * Puts the detail pane back to its placeholder.
 *
 * Called when the show that was in it is gone — either backed out of on a screen too narrow to hold
 * both panes, or removed from the library while it was open. Not `popBackStack()` on its own: that
 * would be right for the first case and wrong for the second, because a removal can arrive while
 * the pane is showing a show that was itself opened over nothing.
 */
internal fun NavController.clearDetailPane() {
    popBackStack(route = Route.NoShowSelected, inclusive = false)
}

/**
 * The show this detail-pane entry is displaying, or null when it is the placeholder.
 *
 * Read from the pane's own back stack rather than held beside it, so the highlighted row in the
 * list and the show in the pane cannot drift apart: they are two readings of one fact.
 */
internal fun NavBackStackEntry?.openPodcastId(): String? = this
    ?.takeIf { it.destination.hasRoute(Route.PodcastDetail::class) }
    ?.toRoute<Route.PodcastDetail>()
    ?.podcastId

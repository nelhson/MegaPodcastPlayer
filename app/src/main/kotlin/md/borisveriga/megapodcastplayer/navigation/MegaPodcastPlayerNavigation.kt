package md.borisveriga.megapodcastplayer.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.serialization.Serializable
import md.borisveriga.megapodcastplayer.R

/**
 * Type-safe navigation routes.
 *
 * Declared as `@Serializable` objects so arguments are checked at compile time rather than passed as
 * loosely typed strings.
 */
sealed interface Route {

    /**
     * What to listen to now: continue, new, up next.
     *
     * The start destination. The library was, and it is the wrong one: a library is an inventory of
     * shows, which answers "what am I subscribed to" and not "what shall I listen to", so every
     * session began show → scroll → tap. This screen answers the second question from facts the
     * app already had.
     */
    @Serializable
    data object Listen : Route

    /**
     * The library of subscribed shows.
     *
     * Still the inventory, and still the only screen that is useful before anything has been
     * played — which is why the Listen tab's empty state leads here.
     */
    @Serializable
    data object Library : Route

    /** Every episode stored on the device, across all shows. */
    @Serializable
    data object Downloads : Route

    /**
     * Apple search and add-by-link.
     *
     * Not a [TopLevelDestination]: the library's add button is the one way in, so this is pushed
     * onto the back stack like any other detail screen and backs out to wherever it was opened from.
     *
     * The screen used to carry a `paste` flag distinguishing "Paste a link" from "Search", because
     * only the former entitled it to read the clipboard. The add button no longer offers that
     * choice — it opens this screen focused, where a paste into the field is one gesture and needs
     * no permission of its own.
     *
     * @property link a URL to open the field with, from a link shared or tapped in another app.
     *   Null for the ordinary add button. The screen classifies it exactly as it classifies typed
     *   text — arriving from outside entitles a link to be *offered*, never to be added.
     */
    @Serializable
    data class Search(val link: String? = null) : Route

    /**
     * One show's episode list.
     *
     * @property podcastId the show's local id.
     * @property episodeId an episode to open the sheet for on arrival, or null. Set by a
     *   new-episode notification that named exactly one episode: the tap then lands on the episode
     *   itself rather than on a list containing it.
     */
    @Serializable
    data class PodcastDetail(
        val podcastId: String,
        val episodeId: String? = null,
    ) : Route

    /**
     * Playback and download preferences.
     *
     * Not a [TopLevelDestination]. Settings is opened rarely and was spending a third of the
     * navigation bar; it is reached from the library's top bar instead, which is where the
     * platform convention puts it.
     */
    @Serializable
    data object Settings : Route

    /**
     * Every moment the user has marked, newest first.
     *
     * A [TopLevelDestination] for the same reason the queue is one: it is a list the user builds
     * over time and comes back to, not a detail of any single show. It is also the one screen whose
     * content is *authored* rather than fetched, which makes burying it two taps deep the wrong
     * trade — a note nobody can find is a note nobody writes.
     */
    @Serializable
    data object Moments : Route

    /**
     * The play queue, with drag-to-reorder.
     *
     * A [TopLevelDestination] rather than a screen pushed from the player. The queue is a list the
     * user builds and edits, not something read once on the way past: reaching it used to mean
     * opening the player first, which put two taps and a full-screen sheet in front of the one
     * screen that answers "what am I listening to next". The player's "up next" link still leads
     * here; it now switches tabs instead of pushing a copy.
     *
     * The player itself is deliberately *not* a route. It is a sheet that grows out of the bar
     * above the navigation bar, so there is no destination to navigate to and nothing for the back
     * stack to disagree with the playback service about.
     */
    @Serializable
    data object Queue : Route
}

/**
 * A destination reachable from the navigation bar / rail.
 *
 * Adding a podcast is deliberately absent: the library screen carries a floating action button for
 * it, and a permanent tab for the same job spent a quarter of the bar on something used once per
 * new show. Settings left for the same reason — a destination opened once a month does not earn a
 * permanent slot, and it is one tap away in the library's top bar.
 *
 * @property route the route object to navigate to.
 * @property labelResId visible label, also used as the accessibility name. A resource id rather
 *   than a string, because this enum is built before there is a composition to resolve it in.
 * @property icon glyph shown in the bar.
 */
enum class TopLevelDestination(
    val route: Route,
    @param:StringRes val labelResId: Int,
    val icon: ImageVector,
) {
    /**
     * First, and the start destination: it is the answer to the question a session opens with.
     *
     * A fifth tab rather than shelves bolted to the top of the library, which was the alternative.
     * Shelves would have kept four tabs and cost a scroll past them before the shows — and, worse,
     * would have made the library two screens at once: an inventory and a recommendation surface.
     */
    LISTEN(Route.Listen, R.string.destination_listen, Icons.Rounded.Headphones),

    LIBRARY(Route.Library, R.string.destination_library, Icons.Rounded.LibraryMusic),

    /**
     * Sits between the library and the downloads because that is the order the three are used in:
     * pick a show, line up what follows, and only then go looking at what is stored on the device.
     */
    QUEUE(Route.Queue, R.string.destination_queue, Icons.AutoMirrored.Rounded.QueueMusic),
    DOWNLOADS(Route.Downloads, R.string.destination_downloads, Icons.Rounded.DownloadDone),

    /**
     * Last, because it is the only tab that is read rather than acted on: the other three are steps
     * in getting something playing, and this one is what listening leaves behind.
     */
    MOMENTS(Route.Moments, R.string.destination_moments, Icons.Rounded.Bookmarks),
}

/**
 * True when [destination] is anywhere in this destination's hierarchy.
 *
 * Used both to light up a tab in the navigation bar and, in [navigateToTopLevel], to recognise a
 * tap on the tab the user is already standing on. A screen pushed *from* a tab — a show opened out
 * of the library — is not in that tab's hierarchy: it is a sibling in the same flat graph, which is
 * exactly why tapping the tab has somewhere to go.
 */
internal fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { node ->
        when (destination) {
            TopLevelDestination.LISTEN -> node.hasRoute(Route.Listen::class)
            TopLevelDestination.LIBRARY -> node.hasRoute(Route.Library::class)
            TopLevelDestination.QUEUE -> node.hasRoute(Route.Queue::class)
            TopLevelDestination.DOWNLOADS -> node.hasRoute(Route.Downloads::class)
            TopLevelDestination.MOMENTS -> node.hasRoute(Route.Moments::class)
        }
    } == true

/**
 * Switches top-level tabs, and always arrives at the tab itself.
 *
 * The obvious spelling — `navigate(route) { popUpTo(start) { saveState = true }; restoreState =
 * true }` — has a hole in it that the user meets immediately: open a show from the library, tap
 * Library, and nothing moves. The pop saves the show, the navigate restores it, and the two cancel
 * out. The same holds for Settings and Search, and for anything pushed from a tab later.
 *
 * So the tab is reached by *returning* to it whenever it is already on the back stack. That pops
 * everything above it in one step, keeps the tab's own entry — and with it the list position the
 * user left — and animates as the back gesture does, which is what the movement actually is. Only
 * a tab that is not on the stack is navigated to, and only then is the saved/restored state of the
 * tab being left behind worth anything.
 *
 * @param destination the tab that was tapped.
 */
internal fun NavController.navigateToTopLevel(destination: TopLevelDestination) {
    // Already standing on it. Re-tapping a tab should not rebuild the screen under the finger.
    if (currentDestination.isOn(destination)) return

    // On the stack somewhere below us: come back to it, dropping whatever was pushed on top.
    if (popBackStack(destination.route, inclusive = false)) return

    // Not on the stack at all: one entry per tab, and give the tab back the state it had.
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

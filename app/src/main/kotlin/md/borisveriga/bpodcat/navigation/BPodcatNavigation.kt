package md.borisveriga.bpodcat.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import md.borisveriga.bpodcat.R

/**
 * Type-safe navigation routes.
 *
 * Declared as `@Serializable` objects so arguments are checked at compile time rather than passed as
 * loosely typed strings.
 */
sealed interface Route {

    /**
     * The Latest feed: every followed show's newest episodes in one chronological list.
     *
     * The start destination. It answers "what is new" — the question a podcast app is opened to
     * ask — which the library, being a list of shows rather than of episodes, never could.
     */
    @Serializable
    data object Home : Route

    /** The library of subscribed shows. */
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
     * @property paste true when the user chose "Paste a link" rather than "Search", which is the
     *   only thing that entitles the screen to read the clipboard. Carried in the route rather than
     *   handed over as a callback so that the distinction survives process death with the back
     *   stack.
     */
    @Serializable
    data class Search(val paste: Boolean = false) : Route

    /**
     * One show's episode list.
     *
     * @property podcastId the show's local id.
     */
    @Serializable
    data class PodcastDetail(val podcastId: String) : Route

    /**
     * Playback and download preferences.
     *
     * Not a [TopLevelDestination]. Settings is opened rarely and was spending a third of the
     * navigation bar; it is reached from the Latest feed's top bar instead, which is where the
     * platform convention puts it.
     */
    @Serializable
    data object Settings : Route

    /**
     * The play queue, with drag-to-reorder.
     *
     * Pushed like [Search] rather than shown as a tab: it is reached from the player, and is empty
     * most of the time.
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
 * permanent slot, and it is one tap away in the Latest feed's top bar.
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
    HOME(Route.Home, R.string.destination_home, Icons.Rounded.NewReleases),
    LIBRARY(Route.Library, R.string.destination_library, Icons.Rounded.LibraryMusic),
    DOWNLOADS(Route.Downloads, R.string.destination_downloads, Icons.Rounded.DownloadDone),
}

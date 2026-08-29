package md.borisveriga.bpodcat.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes.
 *
 * Declared as `@Serializable` objects so arguments are checked at compile time rather than passed as
 * loosely typed strings.
 */
sealed interface Route {

    /** The library of subscribed shows. */
    @Serializable
    data object Library : Route

    /** Every episode stored on the device, across all shows. */
    @Serializable
    data object Downloads : Route

    /** Apple search and add-by-link. */
    @Serializable
    data object Search : Route

    /**
     * One show's episode list.
     *
     * @property podcastId the show's local id.
     */
    @Serializable
    data class PodcastDetail(val podcastId: String) : Route

    /** Playback and download preferences. */
    @Serializable
    data object Settings : Route

    /**
     * The full-screen player.
     *
     * Carries no arguments: what is playing is owned by the playback service, not by the back
     * stack, so a route argument could only ever disagree with it.
     */
    @Serializable
    data object NowPlaying : Route
}

/**
 * A destination reachable from the navigation bar / rail.
 *
 * @property route the route object to navigate to.
 * @property label visible label, also used as the accessibility name.
 * @property icon glyph shown in the bar.
 */
enum class TopLevelDestination(
    val route: Route,
    val label: String,
    val icon: ImageVector,
) {
    LIBRARY(Route.Library, "Library", Icons.Rounded.LibraryMusic),
    DOWNLOADS(Route.Downloads, "Downloads", Icons.Rounded.DownloadDone),
    SEARCH(Route.Search, "Add", Icons.Rounded.Search),
    SETTINGS(Route.Settings, "Settings", Icons.Rounded.Settings),
}

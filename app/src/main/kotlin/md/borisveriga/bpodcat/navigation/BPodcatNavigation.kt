package md.borisveriga.bpodcat.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
 * @property labelResId visible label, also used as the accessibility name. A resource id rather
 *   than a string, because this enum is built before there is a composition to resolve it in.
 * @property icon glyph shown in the bar.
 */
enum class TopLevelDestination(
    val route: Route,
    @param:StringRes val labelResId: Int,
    val icon: ImageVector,
) {
    LIBRARY(Route.Library, R.string.destination_library, Icons.Rounded.LibraryMusic),
    DOWNLOADS(Route.Downloads, R.string.destination_downloads, Icons.Rounded.DownloadDone),
    SEARCH(Route.Search, R.string.destination_search, Icons.Rounded.Search),
    SETTINGS(Route.Settings, R.string.destination_settings, Icons.Rounded.Settings),
}

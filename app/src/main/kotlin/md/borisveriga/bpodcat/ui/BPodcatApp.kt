package md.borisveriga.bpodcat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import md.borisveriga.bpodcat.feature.downloads.DownloadsRoute
import md.borisveriga.bpodcat.feature.library.LibraryRoute
import md.borisveriga.bpodcat.feature.player.MiniPlayerRoute
import md.borisveriga.bpodcat.feature.player.NowPlayingRoute
import md.borisveriga.bpodcat.feature.podcast.PodcastDetailRoute
import md.borisveriga.bpodcat.feature.search.SearchRoute
import md.borisveriga.bpodcat.feature.settings.SettingsRoute
import md.borisveriga.bpodcat.navigation.Route
import md.borisveriga.bpodcat.navigation.TopLevelDestination

/**
 * The app's navigation shell.
 *
 * [NavigationSuiteScaffold] renders a bottom bar when the Fold 7 is closed and a navigation rail
 * when it is open, without the call site knowing which.
 *
 * @param modifier layout modifier.
 * @param navController navigation controller; injected for tests.
 */
@Composable
fun BPodcatApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    selected = currentDestination.isOn(destination),
                    onClick = { navController.navigateToTopLevel(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(stringResource(destination.labelResId)) },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Route.Library,
                // The mini player is a sibling of the nav host rather than part of any screen, so
                // it must not be covered: the host takes what is left after the bar.
                modifier = Modifier.weight(1f),
            ) {
                composable<Route.Library> {
                    LibraryRoute(
                        onPodcastClick = { id -> navController.navigate(Route.PodcastDetail(id)) },
                        onAddClick = { navController.navigateToTopLevel(TopLevelDestination.SEARCH) },
                    )
                }

                composable<Route.Downloads> {
                    DownloadsRoute(
                        onEpisodePlaying = { navController.navigateToNowPlaying() },
                        onBrowseLibrary = {
                            navController.navigateToTopLevel(TopLevelDestination.LIBRARY)
                        },
                    )
                }

                composable<Route.Search> {
                    SearchRoute(
                        onPodcastAdded = { id -> navController.navigate(Route.PodcastDetail(id)) },
                    )
                }

                composable<Route.PodcastDetail> { entry ->
                    // Read purely to fail fast if the route argument is ever dropped; the view model
                    // reads the same value from its SavedStateHandle.
                    entry.toRoute<Route.PodcastDetail>()
                    PodcastDetailRoute(
                        onBack = { navController.popBackStack() },
                        onEpisodePlaying = { navController.navigateToNowPlaying() },
                    )
                }

                composable<Route.Settings> {
                    // The back arrow returns to the library rather than popping, because Settings
                    // is a top-level tab: there may be nothing behind it on a fresh launch.
                    SettingsRoute(
                        onBack = {
                            navController.navigateToTopLevel(TopLevelDestination.LIBRARY)
                        },
                    )
                }

                composable<Route.NowPlaying> {
                    NowPlayingRoute(onCollapse = { navController.popBackStack() })
                }
            }

            // Hidden by the mini player itself when nothing is loaded, and suppressed on the full
            // player, where it would be a duplicate of the controls already on screen.
            if (!currentDestination.isNowPlaying()) {
                MiniPlayerRoute(onExpand = { navController.navigateToNowPlaying() })
            }
        }
    }
}

/** True when [destination] is anywhere in the current destination's hierarchy. */
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { node ->
        when (destination) {
            TopLevelDestination.LIBRARY -> node.hasRoute(Route.Library::class)
            TopLevelDestination.DOWNLOADS -> node.hasRoute(Route.Downloads::class)
            TopLevelDestination.SEARCH -> node.hasRoute(Route.Search::class)
            TopLevelDestination.SETTINGS -> node.hasRoute(Route.Settings::class)
        }
    } == true

/** True while the full player is on screen. */
private fun NavDestination?.isNowPlaying(): Boolean =
    this?.hasRoute(Route.NowPlaying::class) == true

/**
 * Opens the full player.
 *
 * [launchSingleTop] because the player is a single place, not a stack: expanding the mini player
 * twice must not leave two copies to back out of.
 */
private fun NavHostController.navigateToNowPlaying() {
    navigate(Route.NowPlaying) { launchSingleTop = true }
}

/**
 * Switches top-level tabs the way a bottom bar is expected to behave: one entry per tab on the back
 * stack, state preserved, and re-tapping the current tab returns to its root.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

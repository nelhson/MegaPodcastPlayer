package md.borisveriga.bpodcat.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.feature.downloads.DownloadsRoute
import md.borisveriga.bpodcat.feature.home.HomeRoute
import md.borisveriga.bpodcat.feature.library.LibraryRoute
import md.borisveriga.bpodcat.feature.player.PlayerSheetScaffold
import md.borisveriga.bpodcat.feature.player.PlayerSheetState
import md.borisveriga.bpodcat.feature.player.QueueRoute
import md.borisveriga.bpodcat.feature.player.rememberPlayerSheetState
import md.borisveriga.bpodcat.feature.podcast.PodcastDetailRoute
import md.borisveriga.bpodcat.feature.search.SearchRoute
import md.borisveriga.bpodcat.feature.settings.SettingsRoute
import md.borisveriga.bpodcat.navigation.Route
import md.borisveriga.bpodcat.navigation.TopLevelDestination
import md.borisveriga.bpodcat.navigation.popEnter
import md.borisveriga.bpodcat.navigation.popExit
import md.borisveriga.bpodcat.navigation.pushEnter
import md.borisveriga.bpodcat.navigation.pushExit

/**
 * The app's navigation shell.
 *
 * [NavigationSuiteScaffold] renders a bottom bar when the Fold 7 is closed and a navigation rail
 * when it is open, without the call site knowing which. It hides that bar entirely while the player
 * sheet is expanded, so the player owns the whole screen rather than sitting above a row of tabs it
 * has nothing to do with.
 *
 * @param modifier layout modifier.
 * @param pendingPodcastId a show a notification asked to open, or null. Navigated to once and then
 *   reported back through [onPendingPodcastHandled], so a rotation does not repeat the jump.
 * @param onPendingPodcastHandled called after [pendingPodcastId] has been navigated to.
 * @param navController navigation controller; injected for tests.
 * @param playerSheetState how open the player is; hoisted here because the navigation bar and
 *   every "now playing" hand-off react to it.
 */
@Composable
fun BPodcatApp(
    modifier: Modifier = Modifier,
    pendingPodcastId: String? = null,
    onPendingPodcastHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
    playerSheetState: PlayerSheetState = rememberPlayerSheetState(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingPodcastId) {
        val podcastId = pendingPodcastId ?: return@LaunchedEffect
        // launchSingleTop so a second tap on the same notification does not stack a second copy of
        // the show on top of the first.
        navController.navigate(Route.PodcastDetail(podcastId)) { launchSingleTop = true }
        onPendingPodcastHandled()
    }

    val navigationSuiteState = rememberNavigationSuiteScaffoldState()

    LaunchedEffect(playerSheetState.isExpanded) {
        if (playerSheetState.isExpanded) navigationSuiteState.hide() else navigationSuiteState.show()
    }

    NavigationSuiteScaffold(
        modifier = modifier,
        state = navigationSuiteState,
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
        PlayerSheetScaffold(
            sheetState = playerSheetState,
            onOpenQueue = { navController.navigate(Route.Queue) { launchSingleTop = true } },
            modifier = Modifier.fillMaxSize(),
        ) { playerPadding ->
            NavHost(
                navController = navController,
                startDestination = Route.Home,
                // The sheet is drawn over the screens rather than beside them, so the space its
                // collapsed bar occupies has to be given back here or every list's last row would
                // sit permanently underneath it.
                modifier = Modifier.padding(playerPadding),
                enterTransition = { pushEnter() },
                exitTransition = { pushExit() },
                popEnterTransition = { popEnter() },
                popExitTransition = { popExit() },
            ) {
                composable<Route.Home> {
                    HomeRoute(
                        onEpisodePlaying = { scope.launch { playerSheetState.expand() } },
                        onAddPodcast = { navController.navigate(Route.Search()) },
                        onOpenSettings = { navController.navigate(Route.Settings) },
                    )
                }

                composable<Route.Library> {
                    LibraryRoute(
                        onPodcastClick = { id -> navController.navigate(Route.PodcastDetail(id)) },
                        // A plain push now that search is not a tab: it opens on top of the library
                        // and backing out returns there. The two entries differ only in whether the
                        // screen may read the clipboard on arrival.
                        onSearchClick = { navController.navigate(Route.Search()) },
                        onPasteLinkClick = { navController.navigate(Route.Search(paste = true)) },
                    )
                }

                composable<Route.Downloads> {
                    DownloadsRoute(
                        onEpisodePlaying = { scope.launch { playerSheetState.expand() } },
                        onBrowseLibrary = {
                            navController.navigateToTopLevel(TopLevelDestination.LIBRARY)
                        },
                    )
                }

                composable<Route.Search> { entry ->
                    SearchRoute(
                        onBack = { navController.popBackStack() },
                        pasteFromClipboard = entry.toRoute<Route.Search>().paste,
                        // Search drops off the back stack as the new show opens: its job is done,
                        // and backing out of a podcast you just added should land in the library
                        // that now contains it, not in the search results you left behind.
                        onPodcastAdded = { id ->
                            navController.navigate(Route.PodcastDetail(id)) {
                                popUpTo<Route.Search> { inclusive = true }
                            }
                        },
                    )
                }

                composable<Route.PodcastDetail> { entry ->
                    // Read purely to fail fast if the route argument is ever dropped; the view model
                    // reads the same value from its SavedStateHandle.
                    entry.toRoute<Route.PodcastDetail>()
                    PodcastDetailRoute(
                        onBack = { navController.popBackStack() },
                        onEpisodePlaying = { scope.launch { playerSheetState.expand() } },
                    )
                }

                composable<Route.Settings> {
                    // A plain pop now that Settings is pushed from the Latest feed's top bar
                    // rather than being a tab: there is always something behind it.
                    SettingsRoute(onBack = { navController.popBackStack() })
                }

                composable<Route.Queue> {
                    QueueRoute(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

/** True when [destination] is anywhere in the current destination's hierarchy. */
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { node ->
        when (destination) {
            TopLevelDestination.HOME -> node.hasRoute(Route.Home::class)
            TopLevelDestination.LIBRARY -> node.hasRoute(Route.Library::class)
            TopLevelDestination.DOWNLOADS -> node.hasRoute(Route.Downloads::class)
        }
    } == true

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

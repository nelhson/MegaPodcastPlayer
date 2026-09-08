package md.borisveriga.megapodcastplayer.ui

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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.feature.downloads.DownloadsRoute
import md.borisveriga.megapodcastplayer.feature.library.LibraryRoute
import md.borisveriga.megapodcastplayer.feature.listen.ListenRoute
import md.borisveriga.megapodcastplayer.feature.moments.MomentsRoute
import md.borisveriga.megapodcastplayer.feature.player.PlayerSheetScaffold
import md.borisveriga.megapodcastplayer.feature.player.PlayerSheetState
import md.borisveriga.megapodcastplayer.feature.player.QueueRoute
import md.borisveriga.megapodcastplayer.feature.player.rememberPlayerSheetState
import md.borisveriga.megapodcastplayer.feature.podcast.PodcastDetailRoute
import md.borisveriga.megapodcastplayer.feature.search.SearchRoute
import md.borisveriga.megapodcastplayer.feature.settings.SettingsRoute
import md.borisveriga.megapodcastplayer.navigation.LaunchShortcut
import md.borisveriga.megapodcastplayer.navigation.Route
import md.borisveriga.megapodcastplayer.navigation.TopLevelDestination
import md.borisveriga.megapodcastplayer.navigation.isOn
import md.borisveriga.megapodcastplayer.navigation.navigateToShortcut
import md.borisveriga.megapodcastplayer.navigation.navigateToTopLevel
import md.borisveriga.megapodcastplayer.navigation.popEnter
import md.borisveriga.megapodcastplayer.navigation.popExit
import md.borisveriga.megapodcastplayer.navigation.pushEnter
import md.borisveriga.megapodcastplayer.navigation.pushExit

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
 * @param pendingEpisodeId an episode within it to open the sheet for, or null.
 * @param onPendingPodcastHandled called after [pendingPodcastId] has been navigated to.
 * @param pendingSharedLink a podcast link shared or tapped in another app, or null. Consumed the
 *   same way, and for the same reason.
 * @param onPendingSharedLinkHandled called after [pendingSharedLink] has been navigated to.
 * @param pendingOpenPlayer true when the intent that brought the app up was the media
 *   notification's own tap target. Consumed the same way.
 * @param onPendingOpenPlayerHandled called after the player has been expanded.
 * @param pendingShortcut the launcher shortcut this launch came from, or null. Consumed the same
 *   way; see [navigateToShortcut] for why one of the four navigates nowhere.
 * @param onPendingShortcutHandled called after [pendingShortcut] has been acted on.
 * @param navController navigation controller; injected for tests.
 * @param playerSheetState how open the player is; hoisted here because the navigation bar and
 *   every "now playing" hand-off react to it.
 */
@Composable
fun MegaPodcastPlayerApp(
    modifier: Modifier = Modifier,
    pendingPodcastId: String? = null,
    pendingEpisodeId: String? = null,
    onPendingPodcastHandled: () -> Unit = {},
    pendingSharedLink: String? = null,
    onPendingSharedLinkHandled: () -> Unit = {},
    pendingOpenPlayer: Boolean = false,
    onPendingOpenPlayerHandled: () -> Unit = {},
    pendingShortcut: LaunchShortcut? = null,
    onPendingShortcutHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
    playerSheetState: PlayerSheetState = rememberPlayerSheetState(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingPodcastId, pendingEpisodeId) {
        val podcastId = pendingPodcastId ?: return@LaunchedEffect
        // launchSingleTop so a second tap on the same notification does not stack a second copy of
        // the show on top of the first.
        navController.navigate(Route.PodcastDetail(podcastId, pendingEpisodeId)) {
            launchSingleTop = true
        }
        onPendingPodcastHandled()
    }

    // A link shared or tapped elsewhere opens the add screen with the field already filled. Not
    // added, only offered: the tap that adds it is still the user's, which is what keeps an intent
    // any app on the device can send from changing this one's library.
    LaunchedEffect(pendingSharedLink) {
        val link = pendingSharedLink ?: return@LaunchedEffect
        navController.navigate(Route.Search(link)) { launchSingleTop = true }
        onPendingSharedLinkHandled()
    }

    // A tap on the media notification lands *at* the player. The card that was tapped was already
    // showing the episode, the artwork and the transport controls; arriving at a list of shows with
    // a collapsed bar at the bottom asks the user to find their way back to what they were looking
    // at a moment ago.
    LaunchedEffect(pendingOpenPlayer) {
        if (!pendingOpenPlayer) return@LaunchedEffect
        playerSheetState.expand()
        onPendingOpenPlayerHandled()
    }

    // A launcher shortcut names a place in the app, so it is answered here where the graph is,
    // exactly as the notification's show id is. Resume is the exception and moves nothing: it is
    // playback, already started by the activity, and the sheet it opens arrives through
    // `pendingOpenPlayer` above.
    LaunchedEffect(pendingShortcut) {
        val shortcut = pendingShortcut ?: return@LaunchedEffect
        navController.navigateToShortcut(shortcut)
        onPendingShortcutHandled()
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
            // The queue is a tab now, so the link out of the player switches to it rather than
            // pushing a second copy on top of the sheet. The sheet has to come down with it: it is
            // covering the screen the user just asked to see, and it hides the navigation bar they
            // would need to get back out.
            onOpenQueue = {
                scope.launch {
                    playerSheetState.collapse()
                    navController.navigateToTopLevel(TopLevelDestination.QUEUE)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { playerPadding ->
            NavHost(
                navController = navController,
                startDestination = Route.Listen,
                // The sheet is drawn over the screens rather than beside them, so the space its
                // collapsed bar occupies has to be given back here or every list's last row would
                // sit permanently underneath it.
                modifier = Modifier.padding(playerPadding),
                enterTransition = { pushEnter() },
                exitTransition = { pushExit() },
                popEnterTransition = { popEnter() },
                popExitTransition = { popExit() },
            ) {
                composable<Route.Listen> {
                    ListenRoute(
                        onEpisodePlaying = { scope.launch { playerSheetState.expand() } },
                        onBrowseLibrary = {
                            navController.navigateToTopLevel(TopLevelDestination.LIBRARY)
                        },
                    )
                }

                composable<Route.Library> {
                    LibraryRoute(
                        onPodcastClick = { id -> navController.navigate(Route.PodcastDetail(id)) },
                        // A plain push now that search is not a tab: it opens on top of the library
                        // and backing out returns there. The two entries differ only in whether the
                        // screen may read the clipboard on arrival.
                        onSearchClick = { navController.navigate(Route.Search()) },
                        onOpenSettings = { navController.navigate(Route.Settings) },
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
                    // Read to fail fast if the argument is ever dropped; the view model reads the
                    // same value out of its SavedStateHandle.
                    entry.toRoute<Route.Search>()
                    SearchRoute(
                        onBack = { navController.popBackStack() },
                        // A pasted link names one show and nothing else, so search drops off the
                        // back stack as that show opens: backing out should land in the library
                        // that now contains it, not on a screen whose job is done.
                        onPodcastAdded = { id ->
                            navController.navigate(Route.PodcastDetail(id)) {
                                popUpTo<Route.Search> { inclusive = true }
                            }
                        },
                        // Opening a result the library already holds keeps search on the stack:
                        // the user is still reading a list of candidates and backing out of the
                        // show should return them to it.
                        onOpenPodcast = { id -> navController.navigate(Route.PodcastDetail(id)) },
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
                    // A plain pop now that Settings is pushed from the library's top bar rather
                    // than being a tab: there is always something behind it.
                    SettingsRoute(onBack = { navController.popBackStack() })
                }

                composable<Route.Queue> {
                    QueueRoute(
                        onBrowseLibrary = {
                            navController.navigateToTopLevel(TopLevelDestination.LIBRARY)
                        },
                    )
                }

                composable<Route.Moments> {
                    MomentsRoute()
                }
            }
        }
    }
}

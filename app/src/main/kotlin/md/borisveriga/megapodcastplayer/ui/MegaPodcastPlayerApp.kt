package md.borisveriga.megapodcastplayer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.model.OpenPlayerAs
import md.borisveriga.megapodcastplayer.core.model.PlayerMode
import md.borisveriga.megapodcastplayer.feature.downloads.DownloadsRoute
import md.borisveriga.megapodcastplayer.feature.moments.MomentsRoute
import md.borisveriga.megapodcastplayer.feature.player.PlayerSheetScaffold
import md.borisveriga.megapodcastplayer.feature.player.PlayerSheetState
import md.borisveriga.megapodcastplayer.feature.player.PlayerViewModel
import md.borisveriga.megapodcastplayer.feature.player.QueueRoute
import md.borisveriga.megapodcastplayer.feature.player.rememberPlayerSheetState
import md.borisveriga.megapodcastplayer.feature.player.video.VideoRoute
import md.borisveriga.megapodcastplayer.feature.player.video.VideoViewModel
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
 * @param onPendingOpenPlayerHandled called after the player has been opened, as whichever of its
 *   two faces it was left in.
 * @param pendingShortcut the launcher shortcut this launch came from, or null. Consumed the same
 *   way; see [navigateToShortcut] for why one of the four navigates nowhere.
 * @param onPendingShortcutHandled called after [pendingShortcut] has been acted on.
 * @param navController navigation controller; injected for tests.
 * @param playerSheetState how open the player is; hoisted here because the navigation bar and
 *   every "now playing" hand-off react to it.
 * @param playerViewModel the player's view model, the same instance the sheet draws from. The
 *   shell holds it for one thing: which face the player is in is decided here, where the two
 *   faces — the sheet and the video screen — are told apart.
 * @param videoViewModel the picture's view model. Held here, at the activity, and handed to both
 *   places the picture is drawn — the video screen and the collapsed bar it is put away behind —
 *   so that minimising moves one picture rather than ending it and starting another.
 */
// LibraryListDetail's pane navigator is an adaptive type, and it is a parameter of that composable
// so tests can drive it; naming it here is the whole of the opt-in.
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
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
    playerViewModel: PlayerViewModel = hiltViewModel(),
    videoViewModel: VideoViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val scope = rememberCoroutineScope()

    // The one destination that is itself the player: the navigation bar and the sheet both step
    // aside for it, because the episode it shows is the one the sheet would be showing. It is the
    // player's second face, and which face a tap on the bar opens is the remembered `PlayerMode`.
    //
    // Remembered across a recreation, because the back stack cannot be asked on the first frame
    // after one: the entry arrives as state, a frame late, while the NavHost below restores and
    // composes the video screen at once. For that frame the shell used to believe it was not on
    // video, and drew the bar, with a picture of its own, under the screen.
    var wasOnVideo by rememberSaveable { mutableStateOf(false) }
    val onVideo = resolveOnVideo(
        known = currentDestination?.hasRoute(Route.Video::class),
        remembered = wasOnVideo,
    )
    SideEffect { if (currentDestination != null) wasOnVideo = onVideo }

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

    // The one door into the player. Every screen that starts an episode, the player's own two
    // faces and the media notification all come through here, saying what they want — sound,
    // picture, or the player as it was last used — and this is the only place that turns the ask
    // into a face and into the remembered `PlayerMode`. There used to be a function per face,
    // handed out screen by screen, and the screens that were handed neither left the player
    // however the last episode had left it.
    //
    // Sound is answered on the spot, and the picture is told directly as well, without waiting
    // for the mode to be stored and read back. With a video minimised in the bar, the episode just
    // started would otherwise be asked for its picture the moment it loaded and be handed back to
    // sound a moment later: two re-buffers at the start of something the user chose to listen to.
    // The mode is what a later tap on the bar goes by, so an episode the user chose to listen to
    // must not come back as a picture.
    //
    // Picture, and the remembered face, depend on what the player has loaded, so they wait for
    // the view model to say; see `PlayerViewModel.faceFor`. The video screen hides the sheet the
    // moment it arrives, so the collapse need not be waited for: it runs behind the picture, and
    // the sheet is a bar again by the time the user is back. A remembered face changes no mode —
    // it is the mode being read, not set.
    //
    // Becoming sound is a step of its own because the minimised video takes it alone: *Switch to
    // audio* on the bar stops the picture and leaves the bar a bar, where the same words on the
    // video screen go on to open the sheet in the screen's place.
    val becomeAudio: () -> Unit = {
        videoViewModel.exit()
        playerViewModel.setPlayerMode(PlayerMode.AUDIO)
    }
    val openPlayerNow: suspend (String?, OpenPlayerAs) -> Unit = { episodeId, openAs ->
        if (openAs == OpenPlayerAs.AUDIO) becomeAudio()
        when (playerViewModel.faceFor(episodeId, openAs)) {
            PlayerMode.VIDEO -> {
                playerViewModel.setPlayerMode(PlayerMode.VIDEO)
                scope.launch { playerSheetState.collapse() }
                navController.navigate(Route.Video) { launchSingleTop = true }
            }

            PlayerMode.AUDIO -> scope.launch { playerSheetState.expand() }

            // A picture was asked for and the player never loaded the episode: nothing to open.
            null -> Unit
        }
    }
    // Undispatched, so that an ask for sound has told the picture and the mode before this
    // returns, as it did when it was a function of its own.
    val openPlayer: (String?, OpenPlayerAs) -> Unit = { episodeId, openAs ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) { openPlayerNow(episodeId, openAs) }
    }

    // A tap on the media notification lands *at* the player. The card that was tapped was already
    // showing the episode, the artwork and the transport controls; arriving at a list of shows with
    // a collapsed bar at the bottom asks the user to find their way back to what they were looking
    // at a moment ago.
    LaunchedEffect(pendingOpenPlayer) {
        if (!pendingOpenPlayer) return@LaunchedEffect
        // Unless already at the player: the video screen is one of its two faces. Otherwise as it
        // was left, which is the player the tap asked for. Waited for here rather than launched,
        // so the ask is only reported handled once it has been answered.
        if (!onVideo) openPlayerNow(null, OpenPlayerAs.REMEMBERED)
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

    // How many times the tab the user is already standing on has been tapped again (NAV-4).
    //
    // A count rather than a flag, because the same request can be made twice and a boolean that
    // was already true the second time would do nothing. It is held here rather than in each
    // screen because only the shell knows a tap was a *re*-tap; what to do about it is the
    // screen's, and every top-level screen answers it the same way through `ScrollToTopEffect`.
    var reTapCount by rememberSaveable { mutableIntStateOf(0) }

    val navigationSuiteState = rememberNavigationSuiteScaffoldState()

    LaunchedEffect(playerSheetState.isExpanded, onVideo) {
        if (playerSheetState.isExpanded || onVideo) {
            navigationSuiteState.hide()
        } else {
            navigationSuiteState.show()
        }
    }

    NavigationSuiteScaffold(
        modifier = modifier,
        state = navigationSuiteState,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    selected = currentDestination.isOn(destination),
                    onClick = {
                        // False means "you were already there", which is the platform convention's
                        // cue: scroll the list back to the top.
                        if (!navController.navigateToTopLevel(destination)) reTapCount++
                    },
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
            // The *Video* half of the sheet's switch and a tap on the bar of an episode being
            // watched: the episode is the one already loaded.
            onWatch = { openPlayer(null, OpenPlayerAs.VIDEO) },
            onSwitchToAudio = becomeAudio,
            hidden = onVideo,
            modifier = Modifier.fillMaxSize(),
            viewModel = playerViewModel,
            videoViewModel = videoViewModel,
        ) { playerPadding ->
            NavHost(
                navController = navController,
                startDestination = Route.Library,
                // The sheet is drawn over the screens rather than beside them, so the space its
                // collapsed bar occupies has to be given back here or every list's last row would
                // sit permanently underneath it.
                modifier = Modifier.padding(playerPadding),
                enterTransition = { pushEnter() },
                exitTransition = { pushExit() },
                popEnterTransition = { popEnter() },
                popExitTransition = { popExit() },
            ) {
                composable<Route.Library> {
                    // Not LibraryRoute directly: the tab is a list *and* a show, laid out as one
                    // pane or two depending on how much of the Fold is open. See LibraryListDetail
                    // for why the show it opens lives on a graph of its own.
                    LibraryListDetail(
                        // A plain push now that search is not a tab: it opens on top of the library
                        // and backing out returns there. The two entries differ only in whether the
                        // screen may read the clipboard on arrival.
                        onSearchClick = { navController.navigate(Route.Search()) },
                        onOpenSettings = { navController.navigate(Route.Settings) },
                        onOpenPlayer = openPlayer,
                        scrollToTopSignal = reTapCount,
                    )
                }

                composable<Route.Downloads> {
                    DownloadsRoute(
                        onOpenPlayer = openPlayer,
                        onBrowseLibrary = {
                            navController.navigateToTopLevel(TopLevelDestination.LIBRARY)
                        },
                        onOpenSettings = { navController.navigate(Route.Settings) },
                        scrollToTopSignal = reTapCount,
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
                    // The show reached from somewhere that is not the library: a notification, a
                    // search result, a shared link. Full screen at every width, because the library
                    // is not the list it was picked from and putting it beside one would be an
                    // answer to a question the user did not ask. The library tab has its own copy
                    // of this screen in its detail pane; see LibraryListDetail.
                    //
                    // Read purely to fail fast if the route argument is ever dropped; the view model
                    // reads the same value from its SavedStateHandle.
                    entry.toRoute<Route.PodcastDetail>()
                    PodcastDetailRoute(
                        onBack = { navController.popBackStack() },
                        onOpenPlayer = openPlayer,
                    )
                }

                composable<Route.Settings> {
                    // A plain pop now that Settings is pushed from the library's top bar rather
                    // than being a tab: there is always something behind it.
                    SettingsRoute(onBack = { navController.popBackStack() })
                }

                composable<Route.Queue> {
                    QueueRoute(
                        onOpenPlayer = openPlayer,
                        onOpenSettings = { navController.navigate(Route.Settings) },
                        scrollToTopSignal = reTapCount,
                    )
                }

                composable<Route.Moments> {
                    MomentsRoute(
                        onOpenPlayer = openPlayer,
                        onOpenSettings = { navController.navigate(Route.Settings) },
                        scrollToTopSignal = reTapCount,
                    )
                }

                composable<Route.Video> {
                    VideoRoute(
                        // Minimising — and the back gesture, which is the same pop — lands where
                        // the player was opened from, with the episode carrying on in the bar,
                        // picture included: the bar draws from the same view model this screen is
                        // given. The mode is left alone, so a tap on the bar comes back here. The
                        // screen pops itself the same way when the player moves on to an episode
                        // with nothing to show.
                        //
                        // Both exits pop *this* route rather than whatever is on top. The screen
                        // still takes taps while it animates out, and a second tap must find
                        // nothing left to pop instead of taking the screen underneath with it.
                        onCollapse = { navController.popBackStack(Route.Video, inclusive = true) },
                        // The other way out changes what the player is: the picture goes, and the
                        // audio player opens in its place over the same screen.
                        onListen = {
                            if (navController.popBackStack(Route.Video, inclusive = true)) {
                                openPlayer(null, OpenPlayerAs.AUDIO)
                            }
                        },
                        viewModel = videoViewModel,
                    )
                }
            }
        }
    }
}

/**
 * Whether the video screen is the current destination, when the back stack may not have said yet.
 *
 * @param known what the back stack says, or null while it has not reported an entry — the first
 *   frame of a composition, including the first after the activity was recreated.
 * @param remembered what it said the last time it did, saved across the recreation.
 * @return [known] when there is an answer, else [remembered].
 */
internal fun resolveOnVideo(known: Boolean?, remembered: Boolean): Boolean = known ?: remembered

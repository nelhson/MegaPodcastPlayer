package md.borisveriga.megapodcastplayer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.R
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.feature.library.LibraryRoute
import md.borisveriga.megapodcastplayer.feature.podcast.PodcastDetailRoute
import md.borisveriga.megapodcastplayer.navigation.Route
import md.borisveriga.megapodcastplayer.navigation.clearDetailPane
import md.borisveriga.megapodcastplayer.navigation.openPodcastId
import md.borisveriga.megapodcastplayer.navigation.openShowInDetailPane
import md.borisveriga.megapodcastplayer.navigation.rememberDetailPaneGraph

/**
 * The library and one show, side by side when there is room for both.
 *
 * This is the Library tab. On a folded Fold 7 it is exactly what it was before — the library, and a
 * show pushed over it — because [NavigableListDetailPaneScaffold] collapses to a single pane at
 * that width and the pane navigator's back behaviour is the push's back behaviour. Opened out, the
 * same code puts the show beside the library it was picked from, which is the one thing this app
 * has never used the inner display for.
 *
 * **Why a second [NavHost] rather than a `contentKey` on the pane navigator.** The show's view
 * model reads its `podcastId` out of a `SavedStateHandle`, as a screen reached by navigating to it
 * does; giving the detail pane a graph of its own keeps that true, so `PodcastDetailRoute` is the
 * same call here as it is on the outer graph and the view model is scoped, saved and cleared by the
 * same machinery. Its `showBackButton` parameter was written for this call and until now had no
 * caller: the arrow appears only when the list pane is *not* on screen, because an arrow pointing
 * back to a list that is already visible beside it is an arrow pointing at nothing.
 *
 * The two graphs are deliberately separate. The outer one still carries `Route.PodcastDetail` for
 * the shows reached from somewhere other than the library — a notification, a search result, a
 * shared link — and those are full-screen pushes at every width, because the list they would sit
 * beside is not the list the user came from.
 *
 * @param onSearchClick opens the add-a-show screen; on the outer graph, over both panes.
 * @param onOpenSettings opens settings, likewise.
 * @param onEpisodePlaying invoked once a tapped episode has been handed to the player, so the shell
 *   can expand the sheet.
 * @param modifier layout modifier.
 * @param paneNavigator decides how many panes there is room for and which one is in front;
 *   injected for tests.
 * @param detailNavController the detail pane's own graph; injected for tests.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun LibraryListDetail(
    onSearchClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onEpisodePlaying: () -> Unit,
    modifier: Modifier = Modifier,
    paneNavigator: ThreePaneScaffoldNavigator<Nothing> =
        rememberListDetailPaneScaffoldNavigator<Nothing>(),
    detailNavController: NavHostController = rememberNavController(),
) {
    val scope = rememberCoroutineScope()

    val detailEntry by detailNavController.currentBackStackEntryAsState()
    val openPodcastId = detailEntry.openPodcastId()

    val isListPaneVisible =
        paneNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded
    // Both, not just the list. On a folded phone the list is "expanded" whenever it is the pane on
    // screen — including after a show has been backed out of, when the detail pane is merely hidden
    // and still holds it — so asking only about the list would leave a row washed on a screen with
    // nothing beside it.
    val areBothPanesVisible = isListPaneVisible &&
        paneNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded

    // Built here, outside the panes, rather than by the pane's own `NavHost`. On a folded phone the
    // detail pane is not composed until it is shown, so the row tapped in the list reaches a
    // controller whose `NavHost` has never run; see `rememberDetailPaneGraph` for what that cost.
    val detailGraph = detailNavController.rememberDetailPaneGraph {
        composable<Route.NoShowSelected> { NoShowSelectedPane() }

        composable<Route.PodcastDetail> {
            PodcastDetailRoute(
                onBack = {
                    scope.launch {
                        // Two callers, and the pane can tell them apart without being told which.
                        // Where there is a pane to close, closing it is the whole answer, and the
                        // show is left loaded exactly as the system back gesture leaves it — that
                        // gesture goes straight to this navigator and never reaches here, so
                        // anything done after it would make the arrow and the swipe mean two
                        // things.
                        //
                        // Where there is nothing to close, both panes are already open and the
                        // arrow is not drawn, so the only caller left is a show that has just
                        // removed itself. That one has to be cleared, or the pane sits on a screen
                        // whose show is gone and asks to go back forever.
                        if (!paneNavigator.navigateBack()) {
                            detailNavController.clearDetailPane()
                        }
                    }
                },
                onEpisodePlaying = onEpisodePlaying,
                showBackButton = !isListPaneVisible,
            )
        }
    }

    NavigableListDetailPaneScaffold(
        navigator = paneNavigator,
        modifier = modifier,
        listPane = {
            AnimatedPane {
                LibraryRoute(
                    onPodcastClick = { podcastId ->
                        detailNavController.openShowInDetailPane(podcastId)
                        scope.launch {
                            paneNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                        }
                    },
                    onSearchClick = onSearchClick,
                    onOpenSettings = onOpenSettings,
                    // Only while the list is actually beside the show. On a folded phone the tap
                    // takes the user off the library entirely, so there is nothing standing open
                    // for a highlight to point at, and a row left washed under a full-screen push
                    // would be a mark for a fact nobody is looking at.
                    selectedPodcastId = if (areBothPanesVisible) openPodcastId else null,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                // The graph, not a builder: the same instance the controller has been carrying
                // since the first frame, so arriving here refreshes the pane's stack rather than
                // replacing it and throwing away the show that was just opened on it.
                NavHost(navController = detailNavController, graph = detailGraph)
            }
        },
    )
}

/**
 * The detail pane before a show has been picked.
 *
 * Only ever seen on a screen wide enough for two panes: a folded phone shows the list alone until a
 * tap replaces it with a show, and never has a second pane to leave empty.
 */
@Composable
private fun NoShowSelectedPane() {
    EmptyState(
        icon = Icons.Rounded.Podcasts,
        title = stringResource(R.string.library_pane_empty_title),
        description = stringResource(R.string.library_pane_empty_description),
    )
}

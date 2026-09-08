package md.borisveriga.megapodcastplayer.navigation

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [navigateToTopLevel].
 *
 * The bug these exist for is the one a user meets on their first session: open a show out of the
 * library, tap Library, and stay on the show. The naive tab navigation saves the show on the way
 * down and restores it on the way up, so the tap costs a frame and changes nothing.
 *
 * The graph here is the real one minus its screens — the same routes in the same shape, with empty
 * composables — because what is under test is the back stack, not what is drawn on it.
 */
@RunWith(AndroidJUnit4::class)
// The real `MegaPodcastPlayerApplication` binds a Media3 `MediaController` on start-up, which
// Robolectric's fake service binding completes with a null `ComponentName`. Nothing here needs the
// composition root, so a bare Application stands in for it.
@Config(sdk = [34], application = Application::class)
class TopLevelNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: NavHostController

    /**
     * The routes currently on the back stack, oldest first, by their simple names.
     *
     * The graph itself sits at the bottom of every back stack and is not a screen, so it is
     * dropped; arguments are trimmed off — both the path kind a show's id uses and the query kind
     * an optional argument uses — so every screen reads as its own name.
     */
    private val backStack: List<String>
        get() = navController.currentBackStack.value
            .filterNot { it.destination is NavGraph }
            .map { entry ->
                entry.destination.route.orEmpty()
                    .substringAfterLast('.')
                    .substringBefore('/')
                    .substringBefore('?')
            }

    /** Renders the app's graph with empty screens and captures its controller. */
    private fun setUpGraph() {
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Route.Library) {
                composable<Route.Library> {}
                composable<Route.Queue> {}
                composable<Route.Downloads> {}
                composable<Route.Moments> {}
                composable<Route.Search> {}
                composable<Route.Settings> {}
                composable<Route.PodcastDetail> {}
            }
        }
    }

    /** Runs [block] against the controller on the main thread and lets the graph settle. */
    private fun onNav(block: NavHostController.() -> Unit) {
        composeTestRule.runOnIdle { navController.block() }
        composeTestRule.waitForIdle()
    }

    /**
     * The return value is what NAV-4 hangs on. `navigateToTopLevel` cannot scroll a list — the list
     * belongs to a screen and this only moves the back stack — so it reports the one case the
     * caller has to answer itself, and the shell turns that into the re-tap count every top-level
     * screen watches.
     */
    @Test
    fun `re-tapping the tab you are on reports it, and moves nothing`() {
        setUpGraph()

        lateinit var moved: List<Boolean>
        onNav {
            moved = listOf(
                navigateToTopLevel(TopLevelDestination.LIBRARY),
                navigateToTopLevel(TopLevelDestination.DOWNLOADS),
            )
        }

        // False for the tab already under the finger, true for the one that was navigated to.
        assertEquals(listOf(false, true), moved)
        assertEquals(listOf("Library", "Downloads"), backStack)
    }

    @Test
    fun `coming back to a tab further down the stack is a move, not a re-tap`() {
        // The distinction that matters: a show is open over the library, the user taps Library, and
        // the show is dropped. Something happened, so the list must not also be scrolled to the top
        // — the user asked to see the library they left, at the place they left it.
        setUpGraph()

        onNav { navigate(Route.PodcastDetail("show-1")) }

        var moved = false
        onNav { moved = navigateToTopLevel(TopLevelDestination.LIBRARY) }

        assertEquals(true, moved)
        assertEquals(listOf("Library"), backStack)
    }

    @Test
    fun `tapping the tab a pushed screen came from goes back to it`() {
        setUpGraph()

        onNav { navigate(Route.PodcastDetail("show-1")) }
        assertEquals(listOf("Library", "PodcastDetail"), backStack)

        onNav { navigateToTopLevel(TopLevelDestination.LIBRARY) }

        assertEquals(listOf("Library"), backStack)
    }

    @Test
    fun `tapping a different tab from a pushed screen drops that screen`() {
        setUpGraph()

        onNav { navigate(Route.PodcastDetail("show-1")) }
        onNav { navigateToTopLevel(TopLevelDestination.DOWNLOADS) }

        assertEquals(listOf("Library", "Downloads"), backStack)
    }

    @Test
    fun `settings is left behind the same way a show is`() {
        setUpGraph()

        onNav { navigate(Route.Settings) }
        onNav { navigateToTopLevel(TopLevelDestination.LIBRARY) }

        assertEquals(listOf("Library"), backStack)
    }

    @Test
    fun `search and the show it opened both come off when a tab is tapped`() {
        setUpGraph()

        onNav { navigate(Route.Search()) }
        onNav { navigate(Route.PodcastDetail("show-1")) }
        assertEquals(listOf("Library", "Search", "PodcastDetail"), backStack)

        onNav { navigateToTopLevel(TopLevelDestination.MOMENTS) }

        assertEquals(listOf("Library", "Moments"), backStack)
    }

    @Test
    fun `every tab keeps exactly one entry, above the library`() {
        setUpGraph()

        onNav { navigateToTopLevel(TopLevelDestination.QUEUE) }
        onNav { navigateToTopLevel(TopLevelDestination.DOWNLOADS) }
        onNav { navigateToTopLevel(TopLevelDestination.MOMENTS) }

        assertEquals(listOf("Library", "Moments"), backStack)
    }

    @Test
    fun `returning to a tab already on the stack pops rather than pushes`() {
        setUpGraph()

        onNav { navigateToTopLevel(TopLevelDestination.QUEUE) }
        onNav { navigate(Route.PodcastDetail("show-1")) }
        onNav { navigateToTopLevel(TopLevelDestination.QUEUE) }

        assertEquals(listOf("Library", "Queue"), backStack)
    }

    @Test
    fun `tapping the tab you are standing on changes nothing`() {
        setUpGraph()

        onNav { navigateToTopLevel(TopLevelDestination.DOWNLOADS) }
        val entryBefore = composeTestRule.runOnIdle { navController.currentBackStackEntry }

        onNav { navigateToTopLevel(TopLevelDestination.DOWNLOADS) }

        assertEquals(listOf("Library", "Downloads"), backStack)
        // The same entry, not a rebuilt one: a second tap must not throw away the screen's state.
        assertEquals(entryBefore, composeTestRule.runOnIdle { navController.currentBackStackEntry })
    }

    @Test
    fun `the library is reachable from a tab that was opened over it`() {
        setUpGraph()

        onNav { navigateToTopLevel(TopLevelDestination.MOMENTS) }
        onNav { navigateToTopLevel(TopLevelDestination.LIBRARY) }

        assertEquals(listOf("Library"), backStack)
    }
}

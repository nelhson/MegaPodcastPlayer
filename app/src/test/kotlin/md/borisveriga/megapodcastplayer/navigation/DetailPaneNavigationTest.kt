package md.borisveriga.megapodcastplayer.navigation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the library detail pane's own back stack.
 *
 * The graph here is the pane's real one minus its screens, exactly as [TopLevelNavigationTest] does
 * for the app's: what is under test is the shape of the stack, not what is drawn on it. The shape
 * is the point — a pane that shows one show at a time is only one show at a time if opening the
 * second dropped the first, and that is invisible until a back gesture walks a history the user
 * never thought they were building.
 *
 * Whether the pane is on screen is a parameter of the fixture rather than an assumption, because
 * the pane being *absent* is the ordinary case on a folded phone and it is where this graph was
 * once fatal.
 */
@RunWith(AndroidJUnit4::class)
// The real application binds a Media3 controller on start-up, which Robolectric's fake service
// binding completes with a null ComponentName. Nothing here needs the composition root.
@Config(sdk = [34], application = Application::class)
class DetailPaneNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: NavHostController

    /**
     * Whether the detail pane is composed.
     *
     * `AnimatedPane` does not compose a pane it is not showing, so false is a folded phone: the
     * graph exists, the pane's `NavHost` does not.
     */
    private val isPaneOnScreen = mutableStateOf(true)

    /** The routes on the pane's stack, oldest first, by their simple names. */
    private val backStack: List<String>
        get() = navController.currentBackStack.value
            .filterNot { it.destination is NavGraph }
            .map { entry ->
                entry.destination.route.orEmpty()
                    .substringAfterLast('.')
                    .substringBefore('/')
                    .substringBefore('?')
            }

    /**
     * Renders the detail pane's graph with empty screens and captures its controller.
     *
     * The graph is attached the way `LibraryListDetail` attaches it — from outside the pane — and
     * the pane's `NavHost` is then given that same instance, so the two halves of the arrangement
     * this file is about are both under test.
     *
     * @param paneOnScreen false to leave the pane uncomposed, as it is until a row is tapped on a
     *   screen too narrow for two panes.
     */
    private fun setUpPane(paneOnScreen: Boolean = true) {
        isPaneOnScreen.value = paneOnScreen
        composeTestRule.setContent {
            navController = rememberNavController()
            val graph = navController.rememberDetailPaneGraph {
                composable<Route.NoShowSelected> {}
                composable<Route.PodcastDetail> {}
            }
            if (isPaneOnScreen.value) {
                NavHost(navController = navController, graph = graph)
            }
        }
    }

    /** Runs [block] against the controller on the main thread and lets the graph settle. */
    private fun onNav(block: NavHostController.() -> Unit) {
        composeTestRule.runOnIdle { navController.block() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the pane starts on the placeholder`() {
        setUpPane()

        assertEquals(listOf("NoShowSelected"), backStack)
        composeTestRule.runOnIdle {
            assertNull(navController.currentBackStackEntry.openPodcastId())
        }
    }

    @Test
    fun `opening a show replaces the placeholder rather than stacking on it`() {
        setUpPane()

        onNav { openShowInDetailPane("show-1") }

        // One entry, not two. With the placeholder left underneath, the pane's own NavHost owned
        // the back gesture and a folded phone went show -> blank pane -> list; with the show alone
        // on the stack the gesture falls through to the pane scaffold, which closes the pane.
        assertEquals(listOf("PodcastDetail"), backStack)
        composeTestRule.runOnIdle {
            assertEquals("show-1", navController.currentBackStackEntry.openPodcastId())
        }
    }

    @Test
    fun `opening a second show replaces the first rather than stacking on it`() {
        setUpPane()

        onNav { openShowInDetailPane("show-1") }
        onNav { openShowInDetailPane("show-2") }

        assertEquals(listOf("PodcastDetail"), backStack)
        composeTestRule.runOnIdle {
            assertEquals("show-2", navController.currentBackStackEntry.openPodcastId())
        }
    }

    @Test
    fun `re-opening the show already in the pane changes nothing`() {
        setUpPane()

        onNav { openShowInDetailPane("show-1") }
        val entry = composeTestRule.runOnIdle { navController.currentBackStackEntry }

        onNav { openShowInDetailPane("show-1") }

        assertEquals(listOf("PodcastDetail"), backStack)
        // The same entry, not a rebuilt one: `launchSingleTop` is what keeps the show's view model,
        // its scroll position and its open sheet alive when the row under the finger is the row
        // already showing.
        composeTestRule.runOnIdle {
            assertEquals(entry, navController.currentBackStackEntry)
        }
    }

    @Test
    fun `clearing puts the pane back to the placeholder`() {
        setUpPane()

        onNav { openShowInDetailPane("show-1") }
        onNav { clearDetailPane() }

        assertEquals(listOf("NoShowSelected"), backStack)
        composeTestRule.runOnIdle {
            assertNull(navController.currentBackStackEntry.openPodcastId())
        }
    }

    @Test
    fun `clearing a pane that is already empty leaves it empty`() {
        setUpPane()

        // What a show removed from the library while the pane happened to be showing nothing would
        // do. The pane has to survive it rather than pop its own start destination off the stack.
        onNav { clearDetailPane() }

        assertEquals(listOf("NoShowSelected"), backStack)
    }

    @Test
    fun `a show opens on a pane that has never been on screen`() {
        // The crash this fixture exists for: on a folded phone the tap that opens a show happens
        // while the detail pane is hidden, so nothing has composed the `NavHost` that would
        // otherwise have set the graph, and navigating died with "You must call setGraph()".
        setUpPane(paneOnScreen = false)

        onNav { openShowInDetailPane("show-1") }

        assertEquals(listOf("PodcastDetail"), backStack)
        composeTestRule.runOnIdle {
            assertEquals("show-1", navController.currentBackStackEntry.openPodcastId())
        }
    }

    @Test
    fun `the pane arrives showing the show that was opened while it was hidden`() {
        setUpPane(paneOnScreen = false)
        onNav { openShowInDetailPane("show-1") }

        // The pane slides in. Its NavHost sets the graph it was handed, and because that is the
        // instance already attached, the stack is refreshed rather than rebuilt from its start
        // destination — the difference between arriving on the show and arriving on the
        // placeholder.
        composeTestRule.runOnIdle { isPaneOnScreen.value = true }
        composeTestRule.waitForIdle()

        assertEquals(listOf("PodcastDetail"), backStack)
        composeTestRule.runOnIdle {
            assertEquals("show-1", navController.currentBackStackEntry.openPodcastId())
        }
    }
}

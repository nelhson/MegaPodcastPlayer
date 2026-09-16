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
 * Tests for [navigateToShortcut].
 *
 * A shortcut is tapped from outside the app, so it lands on whatever back stack the app happens to
 * have — an empty one on a cold start, a show opened out of the library on a warm one. What these
 * check is that it leaves the same stack behind either way as the ordinary way in would: a tab
 * shortcut leaves what tapping the tab leaves, and the add shortcut leaves what the library's add
 * button leaves.
 *
 * The graph is the real one minus its screens, as in [TopLevelNavigationTest], because what is
 * under test is the back stack rather than anything drawn on it.
 */
@RunWith(AndroidJUnit4::class)
// See TopLevelNavigationTest: the real application binds a Media3 controller nothing here needs.
@Config(sdk = [34], application = Application::class)
class ShortcutNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: NavHostController

    /** The routes on the back stack, oldest first, by their simple names. */
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

    @Test
    fun `the queue shortcut opens the queue tab`() {
        setUpGraph()

        onNav { navigateToShortcut(LaunchShortcut.QUEUE) }

        assertEquals(listOf("Library", "Queue"), backStack)
    }

    @Test
    fun `the downloads shortcut opens the downloads tab`() {
        setUpGraph()

        onNav { navigateToShortcut(LaunchShortcut.DOWNLOADS) }

        assertEquals(listOf("Library", "Downloads"), backStack)
    }

    @Test
    fun `a tab shortcut drops whatever the app was showing, as tapping the tab does`() {
        setUpGraph()

        onNav { navigate(Route.PodcastDetail("show-1")) }
        onNav { navigateToShortcut(LaunchShortcut.DOWNLOADS) }

        // The show is gone rather than buried: arriving from the launcher is arriving at the app,
        // not somewhere on top of a session the user had finished with.
        assertEquals(listOf("Library", "Downloads"), backStack)
    }

    @Test
    fun `the add shortcut pushes the add screen and leaves the app behind it`() {
        setUpGraph()

        onNav { navigateToShortcut(LaunchShortcut.ADD_SHOW) }

        // Pushed, not swapped: backing out of it returns to the app rather than leaving it.
        assertEquals(listOf("Library", "Search"), backStack)
    }

    @Test
    fun `a second add shortcut does not stack a second add screen`() {
        setUpGraph()

        onNav { navigateToShortcut(LaunchShortcut.ADD_SHOW) }
        onNav { navigateToShortcut(LaunchShortcut.ADD_SHOW) }

        assertEquals(listOf("Library", "Search"), backStack)
    }

    @Test
    fun `the resume shortcut moves nothing`() {
        setUpGraph()

        onNav { navigate(Route.Downloads) }
        onNav { navigateToShortcut(LaunchShortcut.RESUME) }

        // Resume is playback, and the player is a sheet over whatever is already there: taking the
        // user somewhere as well would be answering "carry on" by moving them.
        assertEquals(listOf("Library", "Downloads"), backStack)
    }
}

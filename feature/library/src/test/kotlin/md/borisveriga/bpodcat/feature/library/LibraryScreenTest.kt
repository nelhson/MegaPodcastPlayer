package md.borisveriga.bpodcat.feature.library

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.LibraryLayout
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastWithCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [LibraryScreen].
 *
 * Two things on this screen are easy to get subtly wrong and invisible in a preview. The layout
 * toggle must offer the layout you are *not* looking at — an icon of what is already on screen is
 * a puzzle, not a control — and the add button must name both ways of adding a show, since a
 * pasted link working at all used to be mentioned only in an empty state that a library with shows
 * in it never displays.
 *
 * The settings action is covered for a third reason: it is the app's only way into Settings now
 * that the Latest tab it used to live on is gone, so losing it would strand the whole screen.
 *
 * Reordering is covered through its accessibility actions rather than by driving a drag. That is
 * not a compromise: the actions are the only way the library is arrangeable with TalkBack on, so
 * they are worth pinning in their own right, and the drag arithmetic behind them belongs to
 * `ReorderableStateTest` in `:core:designsystem`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(id: String, title: String, newEpisodeCount: Int = 0) = PodcastWithCounts(
        podcast = Podcast(
            id = id,
            itunesId = null,
            title = title,
            author = "Some Author",
            feedUrl = "https://example.com/$id.rss",
            artworkUrl = null,
            description = "",
            addedAt = Instant.EPOCH,
            lastRefreshAt = null,
            etag = null,
            lastModified = null,
            autoRefresh = true,
        ),
        episodeCount = 412,
        newEpisodeCount = newEpisodeCount,
        downloadedCount = 2,
    )

    private fun setScreen(
        layout: LibraryLayout,
        onLayoutChange: (LibraryLayout) -> Unit = {},
        onSearchClick: () -> Unit = {},
        onPasteLinkClick: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onMove: (Int, Int) -> Unit = { _, _ -> },
        podcasts: List<PodcastWithCounts> =
            listOf(entry("a", "Podlodka Podcast", newEpisodeCount = 3)),
    ) {
        composeRule.setContent {
            BPodcatTheme {
                LibraryScreen(
                    uiState = LibraryUiState(
                        podcasts = podcasts,
                        layout = layout,
                        isLoading = false,
                    ),
                    onPodcastClick = {},
                    onSearchClick = onSearchClick,
                    onPasteLinkClick = onPasteLinkClick,
                    onOpenSettings = onOpenSettings,
                    onMove = onMove,
                    onLayoutChange = onLayoutChange,
                    onRefresh = {},
                    onMessageShown = {},
                )
            }
        }
    }

    @Test
    fun `the grid offers the list, and switching asks for the list`() {
        var requested: LibraryLayout? = null
        setScreen(layout = LibraryLayout.GRID, onLayoutChange = { requested = it })

        composeRule.onNodeWithText("Podlodka Podcast").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show as list").performClick()

        assertEquals(LibraryLayout.LIST, requested)
    }

    @Test
    fun `the list offers the grid`() {
        var requested: LibraryLayout? = null
        setScreen(layout = LibraryLayout.LIST, onLayoutChange = { requested = it })

        // The list layout carries what the grid cannot: the counts line.
        composeRule.onNodeWithText("412 episodes · 2 downloaded").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show as grid").performClick()

        assertEquals(LibraryLayout.GRID, requested)
    }

    /**
     * Found by content description rather than by the label a sighted user reads, deliberately.
     * `ExtendedFloatingActionButton` clears the semantics of its own icon and text, so unless each
     * entry is named explicitly the menu is two anonymous buttons — which is what this asserts.
     */
    @Test
    fun `the add button names both ways of adding a show`() {
        var searches = 0
        var pastes = 0
        setScreen(
            layout = LibraryLayout.GRID,
            onSearchClick = { searches++ },
            onPasteLinkClick = { pastes++ },
        )

        // Closed, the menu offers neither.
        composeRule.onNodeWithContentDescription("Paste a link").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Add a podcast").performClick()
        composeRule.onNodeWithContentDescription("Paste a link").performClick()

        assertEquals(1, pastes)
        assertEquals(0, searches)
    }

    @Test
    fun `choosing search closes the menu and opens search`() {
        var searches = 0
        setScreen(layout = LibraryLayout.GRID, onSearchClick = { searches++ })

        composeRule.onNodeWithContentDescription("Add a podcast").performClick()
        composeRule.onNodeWithContentDescription("Search Apple Podcasts").performClick()

        assertEquals(1, searches)
        composeRule.onNodeWithContentDescription("Search Apple Podcasts").assertDoesNotExist()
    }

    @Test
    fun `the top bar opens settings`() {
        var opened = false
        setScreen(layout = LibraryLayout.GRID, onOpenSettings = { opened = true })

        composeRule.onNodeWithContentDescription("Settings").performClick()

        assertTrue(opened)
    }

    @Test
    fun `list rows offer the moves a drag cannot announce`() {
        val moves = mutableListOf<Pair<Int, Int>>()
        setScreen(
            layout = LibraryLayout.LIST,
            onMove = { from, to -> moves += from to to },
            podcasts = listOf(
                entry("a", "Podlodka Podcast"),
                entry("b", "Acquired"),
                entry("c", "Zeitgeist"),
            ),
        )

        composeRule.onNodeWithText("Acquired").performCustomAccessibilityAction("Move up")

        assertEquals(listOf(1 to 0), moves)
    }

    @Test
    fun `grid tiles offer the same moves as rows`() {
        val moves = mutableListOf<Pair<Int, Int>>()
        setScreen(
            layout = LibraryLayout.GRID,
            onMove = { from, to -> moves += from to to },
            podcasts = listOf(
                entry("a", "Podlodka Podcast"),
                entry("b", "Acquired"),
                entry("c", "Zeitgeist"),
            ),
        )

        // A tile is dragged by long press rather than by a handle, which leaves TalkBack with even
        // less to work with than a row does.
        composeRule.onNodeWithText("Acquired").performCustomAccessibilityAction("Move down")

        assertEquals(listOf(1 to 2), moves)
    }

    @Test
    fun `the ends of the library offer only the move that exists`() {
        setScreen(
            layout = LibraryLayout.LIST,
            podcasts = listOf(entry("a", "Podlodka Podcast"), entry("b", "Acquired")),
        )

        // Offering "Move up" on the first row would be a control that silently does nothing.
        composeRule.onNodeWithText("Podlodka Podcast")
            .assertHasNoCustomAccessibilityAction("Move up")
        composeRule.onNodeWithText("Acquired")
            .assertHasNoCustomAccessibilityAction("Move down")
    }
}

/**
 * Invokes a custom accessibility action by its label.
 *
 * Compose offers no matcher for this, and the actions are the whole point of the assertions above:
 * they are what a screen reader is given in place of a gesture it cannot perform.
 */
private fun SemanticsNodeInteraction.performCustomAccessibilityAction(label: String) {
    val actions = fetchSemanticsNode().config[SemanticsActions.CustomActions]
    val action = actions.first { it.label == label }
    action.action()
}

/** Asserts no custom action carries [label]. */
private fun SemanticsNodeInteraction.assertHasNoCustomAccessibilityAction(label: String) {
    val actions = fetchSemanticsNode().config
        .getOrElse(SemanticsActions.CustomActions) { emptyList() }
    assertTrue(
        "Expected no \"$label\" action, found ${actions.map { it.label }}",
        actions.none { it.label == label },
    )
}

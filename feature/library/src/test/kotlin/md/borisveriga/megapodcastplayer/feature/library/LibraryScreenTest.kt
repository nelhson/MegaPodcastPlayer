package md.borisveriga.megapodcastplayer.feature.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.LibraryLayout
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastWithCounts
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
 * Reordering is mostly covered through its accessibility actions rather than by driving a drag.
 * That is not a compromise: the actions are the only way the library is arrangeable with TalkBack
 * on, so they are worth pinning in their own right, and the drag arithmetic behind them belongs to
 * `ReorderableStateTest` in `:core:designsystem`.
 *
 * One real drag is driven all the same. A row is picked up by a long press anywhere on it, and
 * that gesture shares the row with a tap that opens the show and with the list's own scrolling —
 * three things that a plain unit test cannot tell apart, and that a modifier applied in the wrong
 * place would silently reduce to one.
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
        onPodcastClick: (String) -> Unit = {},
        onSearchClick: () -> Unit = {},
        onPasteLinkClick: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onMove: (Int, Int) -> Unit = { _, _ -> },
        onQueueNewest: (PodcastWithCounts) -> Unit = {},
        onMarkAllPlayed: (PodcastWithCounts) -> Unit = {},
        onRemove: (PodcastWithCounts) -> Unit = {},
        podcasts: List<PodcastWithCounts> =
            listOf(entry("a", "Podlodka Podcast", newEpisodeCount = 3)),
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                LibraryScreen(
                    uiState = LibraryUiState(
                        podcasts = podcasts,
                        layout = layout,
                        isLoading = false,
                    ),
                    onPodcastClick = onPodcastClick,
                    onSearchClick = onSearchClick,
                    onPasteLinkClick = onPasteLinkClick,
                    onOpenSettings = onOpenSettings,
                    onMove = onMove,
                    onQueueNewest = onQueueNewest,
                    onMarkAllPlayed = onMarkAllPlayed,
                    onRemove = onRemove,
                    onUndoMarkAllPlayed = {},
                    onLayoutChange = onLayoutChange,
                    onRefresh = {},
                    onMessageShown = {},
                )
            }
        }
    }

    /**
     * The bar is what says which of the three tabs the user is on, so it has to survive the
     * scrolling. It used to be a large bar that took the name with it on the way up.
     */
    @Test
    fun `the tab name stays in the bar once the library is scrolled`() {
        setScreen(
            layout = LibraryLayout.LIST,
            podcasts = List(size = 30) { index -> entry("p$index", "Show $index") },
        )

        composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp() }

        composeRule.onNodeWithText("Library").assertIsDisplayed()
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
    fun `a long press anywhere on a row picks it up, and the drag moves the show`() {
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

        composeRule.onNodeWithText("Acquired").performTouchInput {
            down(center)
            // Held past the system's long-press timeout, which is what separates picking the row
            // up from tapping it or flicking the list.
            advanceEventTime(LONG_PRESS_MS)
            // One row up puts the dragged row's centre inside the row above it.
            moveBy(Offset(0f, -height.toFloat()))
            up()
        }

        assertEquals(listOf(1 to 0), moves)
    }

    @Test
    fun `a tap on a row still opens the show`() {
        var opened: String? = null
        setScreen(
            layout = LibraryLayout.LIST,
            onPodcastClick = { opened = it },
            podcasts = listOf(entry("a", "Podlodka Podcast"), entry("b", "Acquired")),
        )

        // The long press sits on the same row as the click; a gesture detector that claimed the
        // press outright would leave the library unable to open anything.
        composeRule.onNodeWithText("Acquired").performClick()

        assertEquals("b", opened)
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

    @Test
    fun `dragging a row past half its width queues the show's next episode`() {
        var queued: String? = null
        setScreen(
            layout = LibraryLayout.LIST,
            onQueueNewest = { queued = it.podcast.title },
        )

        composeRule.onNodeWithText("Podlodka Podcast").performTouchInput {
            down(centerRight)
            // Slowly, in steps, and past the halfway mark. Slowly because the commit tier answers
            // to distance rather than velocity, and in steps because one jump would be read as a
            // fling and settle by the other rule.
            repeat(SWIPE_STEPS) {
                moveBy(Offset(-width / SWIPE_STEPS.toFloat(), 0f))
                advanceEventTime(SWIPE_STEP_MS)
            }
            up()
        }

        assertEquals("Podlodka Podcast", queued)
    }

    @Test
    fun `a short swipe reveals the buttons rather than queueing anything`() {
        var queued: String? = null
        var marked: String? = null
        setScreen(
            layout = LibraryLayout.LIST,
            onQueueNewest = { queued = it.podcast.title },
            onMarkAllPlayed = { marked = it.podcast.title },
        )

        composeRule.onNodeWithText("Podlodka Podcast").performTouchInput {
            down(centerRight)
            // A short pull: far enough to rest open, nowhere near the commit threshold. The two
            // tiers sharing one gesture is the whole design, and a threshold set wrong would make
            // this fire the full swipe.
            repeat(SWIPE_STEPS) {
                moveBy(Offset(-SHORT_SWIPE_PX / SWIPE_STEPS, 0f))
                advanceEventTime(SWIPE_STEP_MS)
            }
            up()
        }

        assertEquals(null, queued)
        composeRule.onNodeWithText("Mark all played").performClick()
        assertEquals("Podlodka Podcast", marked)
    }

    @Test
    fun `removing a show asks before it happens`() {
        var removed: String? = null
        setScreen(layout = LibraryLayout.LIST, onRemove = { removed = it.podcast.title })

        composeRule.onNodeWithText("Podlodka Podcast")
            .performCustomAccessibilityAction("Remove")

        // Nothing has gone yet: unlike the other two actions this one cannot be offered back, so
        // the friction is in front of it rather than behind it.
        assertEquals(null, removed)
        composeRule.onNodeWithText("Remove Podlodka Podcast?").assertIsDisplayed()
        // The count is what makes the warning decidable rather than merely alarming.
        composeRule.onNodeWithText("2 downloaded episodes will be deleted.").assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(null, removed)
    }

    @Test
    fun `confirming the dialog removes the show`() {
        var removed: String? = null
        setScreen(layout = LibraryLayout.LIST, onRemove = { removed = it.podcast.title })

        composeRule.onNodeWithText("Podlodka Podcast")
            .performCustomAccessibilityAction("Remove")
        // "Remove" is both the swipe button and the dialog's confirm button. The dialog is on top,
        // so its is the one this finds — and the assertion below is what would catch it if not.
        composeRule.onAllNodesWithText("Remove").onLast().performClick()

        assertEquals("Podlodka Podcast", removed)
    }

    @Test
    fun `every swipe action is reachable without a swipe`() {
        // A drag is invisible to a screen reader, so a row that only offered these as a gesture
        // would be a row TalkBack cannot act on at all.
        val queued = mutableListOf<String>()
        val marked = mutableListOf<String>()
        setScreen(
            layout = LibraryLayout.LIST,
            onQueueNewest = { queued += it.podcast.title },
            onMarkAllPlayed = { marked += it.podcast.title },
        )

        composeRule.onNodeWithText("Podlodka Podcast")
            .performCustomAccessibilityAction("Queue next")
        composeRule.onNodeWithText("Podlodka Podcast")
            .performCustomAccessibilityAction("Mark all played")

        assertEquals(listOf("Podlodka Podcast"), queued)
        assertEquals(listOf("Podlodka Podcast"), marked)
    }

    @Test
    fun `the cover grid keeps its long press and gains no swipe`() {
        // A tile is 148dp of artwork: half of one is not a distance a commit threshold can live at,
        // and two revealed buttons would leave no tile. The gesture is confined to the list, and
        // this is what would catch it leaking into the grid.
        var queued: String? = null
        setScreen(layout = LibraryLayout.GRID, onQueueNewest = { queued = it.podcast.title })

        composeRule.onNodeWithText("Podlodka Podcast")
            .assertHasNoCustomAccessibilityAction("Queue next")
        assertEquals(null, queued)
    }
}

/** How many steps a driven swipe is broken into, so it reads as a drag rather than a fling. */
private const val SWIPE_STEPS = 10

/** Milliseconds between those steps; slow enough to stay under the fling velocity. */
private const val SWIPE_STEP_MS = 32L

/**
 * Far enough to rest the row open, nowhere near half its width.
 *
 * Has to clear two floors, not one: Compose swallows a touch slop's worth of the first movement,
 * and what is left must still pass half the two revealed buttons' width. On the 411dp xxhdpi device
 * this class configures, half the row — the commit threshold — is around 616px, so this sits
 * comfortably between the two.
 */
private const val SHORT_SWIPE_PX = 400f

/** Comfortably past the 500ms system long-press timeout the drag gesture waits out. */
private const val LONG_PRESS_MS = 1_000L

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

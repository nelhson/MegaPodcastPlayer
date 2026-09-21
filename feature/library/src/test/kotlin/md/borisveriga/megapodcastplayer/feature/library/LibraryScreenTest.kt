package md.borisveriga.megapodcastplayer.feature.library

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.LibraryFilter
import md.borisveriga.megapodcastplayer.core.model.LibraryLayout
import md.borisveriga.megapodcastplayer.core.model.LibrarySort
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
 *
 * The sort and the narrowing controls are covered for what they do to *each other*: an order the
 * library did not arrange by hand, and a list that has been narrowed, must both withdraw the drag,
 * because a drag applied to either would write an arrangement of the wrong shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The screen's focus manager, captured by [setScreen] for the tests that move focus. */
    private var focusManager: FocusManager? = null

    private fun entry(
        id: String,
        title: String,
        newEpisodeCount: Int = 0,
        unplayedCount: Int = 0,
        author: String = "Some Author",
    ) = PodcastWithCounts(
        podcast = Podcast(
            id = id,
            itunesId = null,
            title = title,
            author = author,
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
        unplayedCount = unplayedCount,
    )

    private fun setScreen(
        layout: LibraryLayout,
        onLayoutChange: (LibraryLayout) -> Unit = {},
        onPodcastClick: (String) -> Unit = {},
        onSearchClick: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        scrollToTopSignal: Int = 0,
        onMove: (Int, Int) -> Unit = { _, _ -> },
        onRemove: (PodcastWithCounts) -> Unit = {},
        onSortChange: (LibrarySort) -> Unit = {},
        onQueryChange: (String) -> Unit = {},
        onOnlyWithNewEpisodesChange: (Boolean) -> Unit = {},
        onClearFilter: () -> Unit = {},
        sort: LibrarySort = LibrarySort.MANUAL,
        filter: LibraryFilter = LibraryFilter.NONE,
        podcasts: List<PodcastWithCounts> =
            listOf(entry("a", "Podlodka Podcast", newEpisodeCount = 3)),
        libraryCount: Int = podcasts.size,
        selectedPodcastId: String? = null,
    ) {
        composeRule.setContent {
            focusManager = LocalFocusManager.current
            MegaPodcastPlayerTheme {
                LibraryScreen(
                    uiState = LibraryUiState(
                        podcasts = podcasts,
                        libraryCount = libraryCount,
                        layout = layout,
                        sort = sort,
                        filter = filter,
                        isLoading = false,
                    ),
                    onPodcastClick = onPodcastClick,
                    onSearchClick = onSearchClick,
                    onOpenSettings = onOpenSettings,
                    onMove = onMove,
                    onRemove = onRemove,
                    onLayoutChange = onLayoutChange,
                    onSortChange = onSortChange,
                    onQueryChange = onQueryChange,
                    onOnlyWithNewEpisodesChange = onOnlyWithNewEpisodesChange,
                    onClearFilter = onClearFilter,
                    onRefresh = {},
                    onMessageShown = {},
                    scrollToTopSignal = scrollToTopSignal,
                    selectedPodcastId = selectedPodcastId,
                )
            }
        }
    }

    /** A library long enough for the narrowing controls to be drawn. */
    private fun longLibrary(size: Int = 10) =
        List(size) { index -> entry("p$index", "Show $index") }

    /**
     * Arriving at the tab hands the screen focus, and focus goes to the first thing that will
     * have it. That used to be the filter field — the only thing here that takes focus in touch
     * mode — and a focused field opens the keyboard over a library nobody had asked to search.
     */
    @Test
    fun `focus arriving at the screen does not land in the filter field`() {
        setScreen(layout = LibraryLayout.LIST, podcasts = longLibrary())

        composeRule.runOnIdle { checkNotNull(focusManager).moveFocus(FocusDirection.Enter) }

        composeRule.onNodeWithText("Find a show").assertIsNotFocused()
    }

    /** The other half: the field is still there to be used, and a tap is how. */
    @Test
    fun `tapping the filter field focuses it`() {
        setScreen(layout = LibraryLayout.LIST, podcasts = longLibrary())

        composeRule.onNodeWithText("Find a show").performClick()

        composeRule.onNodeWithText("Find a show").assertIsFocused()
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

        // Swiped on a row rather than on "the scrollable node": a library this long also draws
        // the narrowing controls, whose chip row scrolls sideways, and there are two of them now.
        composeRule.onNodeWithText("Show 0").performTouchInput { swipeUp() }

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
        composeRule.onNodeWithText("412 episodes").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show as grid").performClick()

        assertEquals(LibraryLayout.GRID, requested)
    }

    /**
     * The badge says *new* — arrived since you last looked — and the row has to be able to say the
     * other thing too, because a show with nothing new and thirty never-started episodes is the
     * case the badge alone reads as "nothing to do here".
     */
    @Test
    fun `a row reports new and unplayed as different numbers`() {
        setScreen(
            layout = LibraryLayout.LIST,
            podcasts = listOf(
                entry("a", "Podlodka Podcast", newEpisodeCount = 3, unplayedCount = 37),
            ),
        )

        composeRule.onNodeWithText("412 episodes · 37 unplayed").assertIsDisplayed()
        // The third count is a mark with a number rather than words; it announces itself.
        composeRule.onNodeWithContentDescription("2 downloaded").assertExists()
        // The badge's own number is the new one, and it is announced in those words.
        composeRule.onNodeWithText("Podlodka Podcast")
            .assertStateDescription("3 new episodes")
    }

    @Test
    fun `the sort menu offers every order and reports the one chosen`() {
        var requested: LibrarySort? = null
        setScreen(layout = LibraryLayout.LIST, onSortChange = { requested = it })

        composeRule.onNodeWithContentDescription("Sort shows").performClick()
        composeRule.onNodeWithText("A–Z").performClick()

        assertEquals(LibrarySort.TITLE, requested)
    }

    /** The chip is labelled with the order that is on, not with the word "Sort". */
    @Test
    fun `the sort chip says which order is applied`() {
        setScreen(layout = LibraryLayout.LIST, sort = LibrarySort.RECENTLY_UPDATED)

        composeRule.onNodeWithText("Recently updated").assertIsDisplayed()
    }

    /**
     * A drag in a computed order would be thrown away by the next emission, and the accessibility
     * actions are the only part of it a test can see.
     */
    @Test
    fun `a computed order withdraws the reorder actions`() {
        setScreen(
            layout = LibraryLayout.LIST,
            sort = LibrarySort.TITLE,
            podcasts = listOf(entry("a", "Podlodka Podcast"), entry("b", "Acquired")),
        )

        composeRule.onNodeWithText("Podlodka Podcast")
            .assertHasNoCustomAccessibilityAction("Move down")
        // The swipe is untouched by the order: removing a show means the same thing in all four.
        composeRule.onNodeWithText("Podlodka Podcast")
            .assertHasCustomAccessibilityAction("Remove")
    }

    @Test
    fun `a narrowed list withdraws the reorder actions too`() {
        setScreen(
            layout = LibraryLayout.LIST,
            filter = LibraryFilter(query = "pod"),
            podcasts = listOf(entry("a", "Podlodka Podcast")),
            libraryCount = 12,
        )

        composeRule.onNodeWithText("Podlodka Podcast")
            .assertHasNoCustomAccessibilityAction("Move down")
    }

    /** A search field pointing at three shows is a control aimed at something already visible. */
    @Test
    fun `a short library is offered no narrowing controls`() {
        setScreen(layout = LibraryLayout.LIST)

        composeRule.onNodeWithText("Find a show").assertDoesNotExist()
        composeRule.onNodeWithText("Has new episodes").assertDoesNotExist()
        // The order, unlike the narrowing, is worth offering at any size.
        composeRule.onNodeWithContentDescription("Sort shows").assertIsDisplayed()
    }

    @Test
    fun `a long library is offered the filter field and the new chip`() {
        val typed = mutableListOf<String>()
        var onlyNew: Boolean? = null
        setScreen(
            layout = LibraryLayout.LIST,
            onQueryChange = { typed += it },
            onOnlyWithNewEpisodesChange = { onlyNew = it },
            podcasts = longLibrary(),
        )

        composeRule.onNodeWithText("Find a show").performTextInput("acq")
        composeRule.onNodeWithText("Has new episodes").performClick()

        assertEquals(listOf("acq"), typed)
        assertEquals(true, onlyNew)
    }

    /**
     * A filter that matches nothing is not an empty library. Offering "search Apple Podcasts" to
     * someone who mistyped the name of a show they already follow answers a question nobody asked.
     */
    @Test
    fun `a filter that matches nothing offers the way back`() {
        var cleared = 0
        setScreen(
            layout = LibraryLayout.LIST,
            onClearFilter = { cleared++ },
            filter = LibraryFilter(query = "nothing matches this"),
            podcasts = emptyList(),
            libraryCount = 12,
        )

        composeRule.onNodeWithText("No podcasts yet").assertDoesNotExist()
        composeRule.onNodeWithText("No shows match").assertIsDisplayed()
        composeRule.onNodeWithText("Clear filter").performClick()

        assertEquals(1, cleared)
    }

    /**
     * The add button used to open a two-item menu; adding a show therefore cost two taps in the
     * common case. This pins the replacement: one tap, straight into search, with no intermediate
     * choice to make.
     */
    @Test
    fun `the add button opens search on the first tap`() {
        var searches = 0
        setScreen(layout = LibraryLayout.GRID, onSearchClick = { searches++ })

        composeRule.onNodeWithContentDescription("Add a podcast").performClick()

        assertEquals(1, searches)
        // Nothing intermediate appeared: the old menu's entries are gone entirely, so a second tap
        // would be on nothing.
        composeRule.onNodeWithContentDescription("Search Apple Podcasts").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Paste a link").assertDoesNotExist()
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
    fun `a long drag reveals the button rather than committing anything`() {
        // The row has no full-swipe tier at all: unsubscribing is the only thing on offer and it is
        // far too large to fire on a gesture. However far the row is pulled, it can only open.
        var removed: String? = null
        setScreen(
            layout = LibraryLayout.LIST,
            onRemove = { removed = it.podcast.title },
        )

        composeRule.onNodeWithText("Podlodka Podcast").performTouchInput {
            down(centerRight)
            // Slowly and in steps, so this reads as a drag rather than a fling — a fling settles by
            // velocity, and it is the distance that would have committed a full swipe.
            repeat(SWIPE_STEPS) {
                moveBy(Offset(-width / SWIPE_STEPS.toFloat(), 0f))
                advanceEventTime(SWIPE_STEP_MS)
            }
            up()
        }

        assertEquals(null, removed)
        composeRule.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun `a short swipe reveals the remove button`() {
        var removed: String? = null
        setScreen(layout = LibraryLayout.LIST, onRemove = { removed = it.podcast.title })

        composeRule.onNodeWithText("Podlodka Podcast").performTouchInput {
            down(centerRight)
            // Far enough to rest the row open, which is all this gesture can do.
            repeat(SWIPE_STEPS) {
                moveBy(Offset(-SHORT_SWIPE_PX / SWIPE_STEPS, 0f))
                advanceEventTime(SWIPE_STEP_MS)
            }
            up()
        }

        assertEquals(null, removed)
        // The revealed button is there to be chosen rather than triggered: tapping it opens the
        // confirmation, which is what proves the reveal happened.
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.onNodeWithText("Remove Podlodka Podcast?").assertIsDisplayed()
    }

    @Test
    fun `removing a show asks before it happens`() {
        var removed: String? = null
        setScreen(layout = LibraryLayout.LIST, onRemove = { removed = it.podcast.title })

        composeRule.onNodeWithText("Podlodka Podcast")
            .performCustomAccessibilityAction("Remove")

        // Nothing has gone yet: this cannot be offered back afterwards, so the friction is in
        // front of it rather than behind it.
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
    fun `the swipe action is reachable without a swipe`() {
        // A drag is invisible to a screen reader, so a row that only offered this as a gesture
        // would be a row TalkBack cannot act on at all.
        var removed: String? = null
        setScreen(
            layout = LibraryLayout.LIST,
            onRemove = { removed = it.podcast.title },
        )

        composeRule.onNodeWithText("Podlodka Podcast")
            .performCustomAccessibilityAction("Remove")
        // Removal confirms first, so reaching it is only half the journey.
        composeRule.onAllNodesWithText("Remove").onLast().performClick()

        assertEquals("Podlodka Podcast", removed)
    }

    @Test
    fun `the cover grid gains no swipe`() {
        // A tile is 148dp of artwork, and a revealed button would leave no tile. The gesture is
        // confined to the list, and this is what would catch it leaking into the grid: a swipe
        // across a tile must do nothing at all, not open the removal the menu now offers.
        var removed: String? = null
        setScreen(layout = LibraryLayout.GRID, onRemove = { removed = it.podcast.title })

        composeRule.onNodeWithText("Podlodka Podcast").performTouchInput {
            down(centerRight)
            repeat(SWIPE_STEPS) {
                moveBy(Offset(-SHORT_SWIPE_PX / SWIPE_STEPS, 0f))
                advanceEventTime(SWIPE_STEP_MS)
            }
            up()
        }

        composeRule.onAllNodesWithText("Remove").assertCountEquals(0)
        assertEquals(null, removed)
    }

    /**
     * LIB-4. The grid used to be the layout a show could not be got out of: the list has its
     * swipe and the show's own page has its overflow, and a library kept as covers had neither.
     * The press that rearranges the grid does this too, told apart by whether it travelled.
     */
    @Test
    fun `a press held and released in place opens the tile's menu`() {
        setScreen(layout = LibraryLayout.GRID)

        composeRule.onNodeWithText("Podlodka Podcast").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }

        composeRule.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun `the tile's menu removes the show, once confirmed`() {
        var removed: String? = null
        setScreen(layout = LibraryLayout.GRID, onRemove = { removed = it.podcast.title })

        composeRule.onNodeWithText("Podlodka Podcast").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }
        composeRule.onNodeWithText("Remove").performClick()

        // The grid confirms through the same dialog the list does; reaching the action is half
        // the journey, exactly as it is from a swipe.
        composeRule.onNodeWithText("Remove Podlodka Podcast?").assertIsDisplayed()
        assertEquals(null, removed)

        composeRule.onAllNodesWithText("Remove").onLast().performClick()
        assertEquals("Podlodka Podcast", removed)
    }

    /**
     * The two halves of one gesture. A press that travels is a rearrangement and must not leave a
     * menu behind it — which is the failure this whole item risks, since a menu opening at the end
     * of every drag would make the grid unusable rather than merely incomplete.
     */
    @Test
    fun `a press that moves rearranges the grid and opens nothing`() {
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

        composeRule.onNodeWithText("Acquired").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            // One tile to the left puts the dragged tile's centre inside its neighbour's.
            moveBy(Offset(-width.toFloat(), 0f))
            up()
        }

        assertEquals(listOf(1 to 0), moves)
        composeRule.onAllNodesWithText("Remove").assertCountEquals(0)
    }

    /**
     * The drag and the menu are withdrawn by different things. An order the library did not
     * arrange by hand takes the drag away — positions on screen are not positions in the library —
     * but removing a show means the same thing in every order, so the menu stays. Without this the
     * only way out of a subscription would depend on which order the grid happened to be in.
     */
    @Test
    fun `the menu survives an order the grid cannot be dragged in`() {
        val moves = mutableListOf<Pair<Int, Int>>()
        setScreen(
            layout = LibraryLayout.GRID,
            sort = LibrarySort.TITLE,
            onMove = { from, to -> moves += from to to },
            podcasts = listOf(entry("a", "Podlodka Podcast"), entry("b", "Acquired")),
        )

        composeRule.onNodeWithText("Acquired").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            moveBy(Offset(-width.toFloat(), 0f))
            up()
        }
        assertEquals(emptyList<Pair<Int, Int>>(), moves)

        composeRule.onNodeWithText("Acquired").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }
        composeRule.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun `a tap on a tile still opens the show`() {
        var opened: String? = null
        setScreen(
            layout = LibraryLayout.GRID,
            onPodcastClick = { opened = it },
            podcasts = listOf(entry("a", "Podlodka Podcast"), entry("b", "Acquired")),
        )

        // Three gestures now share a tile — tap, hold, drag — and a detector that claimed the
        // press outright would leave the grid unable to open anything.
        composeRule.onNodeWithText("Acquired").performClick()

        assertEquals("b", opened)
    }

    /**
     * A menu is a way of reaching a thing; the thing is what a screen reader is handed. Without
     * this the grid would be removable only by a gesture TalkBack cannot perform.
     */
    @Test
    fun `the tile publishes its removal as an accessibility action`() {
        var removed: String? = null
        setScreen(layout = LibraryLayout.GRID, onRemove = { removed = it.podcast.title })

        composeRule.onNodeWithText("Podlodka Podcast")
            .performCustomAccessibilityAction("Remove")
        composeRule.onAllNodesWithText("Remove").onLast().performClick()

        assertEquals("Podlodka Podcast", removed)
    }

    /**
     * The two-pane library's one addition to this screen, and the reason it is one row and not two:
     * a wash on more than one row would be claiming the pane is showing more than one show.
     */
    @Test
    fun `only the show the detail pane is showing is marked selected`() {
        setScreen(
            layout = LibraryLayout.LIST,
            podcasts = listOf(
                entry("a", "Podlodka Podcast"),
                entry("b", "Acquired"),
            ),
            selectedPodcastId = "b",
        )

        composeRule.onNodeWithText("Acquired").assertSelected(expected = true)
        composeRule.onNodeWithText("Podlodka Podcast").assertSelected(expected = false)
    }

    /** The grid answers it the same way; the library is one list drawn two ways. */
    @Test
    fun `the grid marks the open show too`() {
        setScreen(
            layout = LibraryLayout.GRID,
            podcasts = listOf(
                entry("a", "Podlodka Podcast"),
                entry("b", "Acquired"),
            ),
            selectedPodcastId = "b",
        )

        composeRule.onNodeWithText("Acquired").assertSelected(expected = true)
        composeRule.onNodeWithText("Podlodka Podcast").assertSelected(expected = false)
    }

    /**
     * The folded phone, where a tap leaves the library entirely. Nothing is standing open beside
     * the list, so nothing is selected — and the rows must not say otherwise, in pixels or to
     * TalkBack.
     */
    @Test
    fun `with no pane beside it the library marks nothing`() {
        setScreen(
            layout = LibraryLayout.LIST,
            podcasts = listOf(entry("a", "Podlodka Podcast")),
        )

        composeRule.onNodeWithText("Podlodka Podcast").assertSelected(expected = false)
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

/** Asserts one custom action carries [label]; the counterpart of the assertion above. */
private fun SemanticsNodeInteraction.assertHasCustomAccessibilityAction(label: String) {
    val actions = fetchSemanticsNode().config
        .getOrElse(SemanticsActions.CustomActions) { emptyList() }
    assertTrue(
        "Expected a \"$label\" action, found ${actions.map { it.label }}",
        actions.any { it.label == label },
    )
}

/**
 * Asserts what a merged row announces beyond its text.
 *
 * The library's new-episode count is a state description rather than a label, because it is a fact
 * about the show that changes while its name does not.
 */
private fun SemanticsNodeInteraction.assertStateDescription(expected: String) {
    assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.StateDescription])
}

/**
 * Asserts whether a row or tile tells accessibility services it is selected.
 *
 * Absent rather than false when nothing is selected: a library with no detail pane beside it is not
 * answering a question about selection at all.
 */
private fun SemanticsNodeInteraction.assertSelected(expected: Boolean) {
    val selected = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)
    assertEquals(if (expected) true else null, selected)
}

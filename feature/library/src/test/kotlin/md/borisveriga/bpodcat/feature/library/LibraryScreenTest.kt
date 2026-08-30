package md.borisveriga.bpodcat.feature.library

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
    ) {
        composeRule.setContent {
            BPodcatTheme {
                LibraryScreen(
                    uiState = LibraryUiState(
                        podcasts = listOf(entry("a", "Podlodka Podcast", newEpisodeCount = 3)),
                        layout = layout,
                        isLoading = false,
                    ),
                    onPodcastClick = {},
                    onSearchClick = onSearchClick,
                    onPasteLinkClick = onPasteLinkClick,
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
}

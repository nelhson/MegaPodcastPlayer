package md.borisveriga.megapodcastplayer.feature.search

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.PodcastSearchResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [SearchScreen].
 *
 * The case worth protecting is the pasted link. It used to be a button wedged under the text field,
 * which is the least visible place on the screen for the app's only way of adding a show Apple does
 * not list; it is now a card above the results, and it has to name what it recognised before any
 * request goes out — a YouTube playlist and an RSS feed are not the same offer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun result(
        id: Long,
        title: String,
        feedUrl: String? = "https://example.com/feed.rss",
    ) = PodcastSearchResult(
        itunesId = id,
        title = title,
        author = "Some Author",
        feedUrl = feedUrl,
        artworkUrl = null,
        episodeCount = 500,
        genres = listOf("Technology"),
    )

    private fun setScreen(
        uiState: SearchUiState,
        onAddLink: () -> Unit = {},
        onAddResult: (PodcastSearchResult) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                SearchScreen(
                    uiState = uiState,
                    onQueryChange = {},
                    onAddLink = onAddLink,
                    onAddResult = onAddResult,
                    onMessageShown = {},
                    onPodcastAdded = {},
                    onBack = onBack,
                )
            }
        }
    }

    @Test
    fun `a pasted feed link is offered as a card, not hidden under the field`() {
        var adds = 0
        setScreen(
            SearchUiState(query = "https://example.com/feed.rss", isLink = true),
            onAddLink = { adds++ },
        )

        composeRule.onNodeWithText("This looks like a podcast link").assertExists()
        composeRule.onNodeWithText("Add this link").performClick()

        assertEquals(1, adds)
    }

    @Test
    fun `a youtube playlist says so before anything is fetched`() {
        setScreen(
            SearchUiState(
                query = "https://www.youtube.com/playlist?list=PL1",
                isLink = true,
                isYouTubeLink = true,
            ),
        )

        composeRule.onNodeWithText("Add this YouTube playlist").assertExists()
    }

    @Test
    fun `ordinary text offers no link card`() {
        setScreen(SearchUiState(query = "podlodka", results = listOf(result(1L, "Podlodka"))))

        composeRule.onNodeWithText("This looks like a podcast link").assertDoesNotExist()
    }

    @Test
    fun `a result can be added`() {
        var added: PodcastSearchResult? = null
        val row = result(1L, "Podlodka Podcast")
        setScreen(SearchUiState(query = "podlodka", results = listOf(row)), onAddResult = { added = it })

        composeRule.onNodeWithText("Podlodka Podcast").performClick()

        assertEquals(row, added)
    }

    @Test
    fun `an apple exclusive explains itself instead of failing after the tap`() {
        var added: PodcastSearchResult? = null
        setScreen(
            SearchUiState(
                query = "exclusive",
                results = listOf(result(2L, "Exclusive Show", feedUrl = null)),
            ),
            onAddResult = { added = it },
        )

        composeRule.onNodeWithText("Apple Podcasts exclusive — no RSS feed to download").assertExists()
        composeRule.onNodeWithText("Exclusive Show").performClick()

        assertEquals(null, added)
    }

    @Test
    fun `the search bar carries the way back`() {
        var backs = 0
        setScreen(SearchUiState(), onBack = { backs++ })

        composeRule.onNodeWithContentDescription("Back to the library").performClick()

        assertEquals(1, backs)
    }
}

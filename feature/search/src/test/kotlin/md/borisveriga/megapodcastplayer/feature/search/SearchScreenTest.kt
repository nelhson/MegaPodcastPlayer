package md.borisveriga.megapodcastplayer.feature.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastPreview
import md.borisveriga.megapodcastplayer.core.model.PodcastSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * A loaded preview of [row], as the repository would return it.
     *
     * @param row the search result the sheet was opened from.
     * @param episodeTitles the episodes it carries, newest first.
     */
    private fun preview(row: PodcastSearchResult, episodeTitles: List<String>) = PodcastPreview(
        podcast = Podcast(
            id = "podcast-1",
            itunesId = row.itunesId,
            title = row.title,
            author = row.author,
            feedUrl = "https://example.com/feed.rss",
            artworkUrl = null,
            description = "A show about software.",
            addedAt = Instant.EPOCH,
            lastRefreshAt = null,
            etag = null,
            lastModified = null,
            autoRefresh = true,
        ),
        episodes = episodeTitles.mapIndexed { index, title ->
            Episode(
                id = "episode-$index",
                podcastId = "podcast-1",
                guid = "guid-$index",
                title = title,
                description = "",
                audioUrl = "https://cdn.example.com/$index.mp3",
                artworkUrl = null,
                durationMs = 3_600_000L,
                publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
                sizeBytes = null,
            )
        },
        totalEpisodeCount = 412,
    )

    /**
     * Puts text on the device clipboard, as another app would have left it.
     *
     * @param text what to copy.
     */
    private fun copyToClipboard(text: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("test", text))
    }

    private fun setScreen(
        uiState: SearchUiState,
        onAddLink: () -> Unit = {},
        onOpenPreview: (PodcastSearchResult) -> Unit = {},
        onSubscribe: () -> Unit = {},
        onPlayPreviewEpisode: (Episode) -> Unit = {},
        onOpenPodcast: (String) -> Unit = {},
        onBack: () -> Unit = {},
        onQueryChange: (String) -> Unit = {},
        onClearRecentSearches: () -> Unit = {},
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                SearchScreen(
                    uiState = uiState,
                    onQueryChange = onQueryChange,
                    onSearchCommitted = {},
                    onClearRecentSearches = onClearRecentSearches,
                    onAddLink = onAddLink,
                    onOpenPreview = onOpenPreview,
                    onDismissPreview = {},
                    onSubscribe = onSubscribe,
                    onPlayPreviewEpisode = onPlayPreviewEpisode,
                    onMessageShown = {},
                    onNavigationHandled = {},
                    onPodcastAdded = {},
                    onOpenPodcast = onOpenPodcast,
                    onBack = onBack,
                )
            }
        }
    }

    /**
     * ADD-5. The field opens on nothing, and what actually happens on this screen is the same show
     * looked up twice because the first attempt was made on the other device.
     */
    @Test
    fun `an empty field offers what has been searched for before`() {
        var typed: String? = null
        setScreen(
            SearchUiState(recentSearches = listOf("podlodka", "acquired")),
            onQueryChange = { typed = it },
        )

        composeRule.onNodeWithText("Recent searches").assertExists()
        composeRule.onNodeWithText("acquired").performClick()

        assertEquals("acquired", typed)
    }

    @Test
    fun `the remembered terms can be forgotten`() {
        var cleared = false
        setScreen(
            SearchUiState(recentSearches = listOf("podlodka")),
            onClearRecentSearches = { cleared = true },
        )

        composeRule.onNodeWithText("Clear").performClick()

        assertTrue(cleared)
    }

    /** They are an offer for an empty field; over a list of results they would be in the way. */
    @Test
    fun `the remembered terms go away once something is typed`() {
        setScreen(
            SearchUiState(
                query = "acq",
                results = listOf(result(id = 1L, title = "Acquired")),
                recentSearches = listOf("podlodka"),
            ),
        )

        composeRule.onNodeWithText("Recent searches").assertDoesNotExist()
    }

    /**
     * ADD-4. A link is on the clipboard because it was copied in another app a moment ago and the
     * user came here to paste it; offering the paste is shorter than the paste.
     */
    @Test
    fun `a podcast link on the clipboard is offered as a chip`() {
        copyToClipboard("https://example.com/feed.rss")
        var typed: String? = null
        setScreen(SearchUiState(), onQueryChange = { typed = it })

        composeRule.onNodeWithText("Paste link").performClick()

        assertEquals("https://example.com/feed.rss", typed)
    }

    /** Only for text the app could actually add, so the chip never appears over a shopping list. */
    @Test
    fun `anything else on the clipboard is left alone`() {
        copyToClipboard("milk, bread, a new podcast app")
        setScreen(SearchUiState())

        composeRule.onNodeWithText("Paste link").assertDoesNotExist()
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

    /**
     * The sheet is a look at a show, and everything on it has to be readable before anything is
     * committed to: what the show says about itself, what it last published, and — the part a
     * description cannot do — two minutes of it.
     */
    @Test
    fun `the preview sheet shows the show, its latest episodes, and plays one`() {
        var played: Episode? = null
        var subscribed = 0
        val row = result(1L, "Podlodka Podcast")
        setScreen(
            SearchUiState(
                query = "podlodka",
                results = listOf(row),
                preview = PreviewUiState(
                    result = row,
                    isLoading = false,
                    preview = preview(row, episodeTitles = listOf("Episode 402", "Episode 401")),
                ),
            ),
            onSubscribe = { subscribed++ },
            onPlayPreviewEpisode = { played = it },
        )

        composeRule.onNodeWithText("Latest episodes").assertExists()
        composeRule.onNodeWithText("Episode 402").assertExists()

        composeRule.onNodeWithContentDescription("Play Episode 402").performClick()
        assertEquals("Episode 402", played?.title)
        // Playing is not subscribing, and the whole sheet rests on that being true.
        assertEquals(0, subscribed)

        composeRule.onNodeWithText("Subscribe").performClick()
        assertEquals(1, subscribed)
    }

    /** A show Apple lists but publishes no feed for cannot be previewed and cannot be added. */
    @Test
    fun `a preview with no feed says so and offers no subscribe`() {
        val row = result(2L, "Exclusive Show", feedUrl = null)
        setScreen(
            SearchUiState(
                query = "exclusive",
                results = listOf(row),
                preview = PreviewUiState(
                    result = row,
                    isLoading = false,
                    error = PreviewError.NoFeed,
                ),
            ),
        )

        composeRule
            .onNodeWithText("This show publishes no feed, so there is nothing to play or follow.")
            .assertExists()
        composeRule.onNodeWithText("Subscribe").assertIsNotEnabled()
    }

    @Test
    fun `ordinary text offers no link card`() {
        setScreen(SearchUiState(query = "podlodka", results = listOf(result(1L, "Podlodka"))))

        composeRule.onNodeWithText("This looks like a podcast link").assertDoesNotExist()
    }

    /**
     * Tapping a result used to subscribe on the spot, which made looking at an unfamiliar show a
     * subscribe-and-unsubscribe — and unsubscribing is the one action here that cannot be undone.
     */
    @Test
    fun `a result opens a preview rather than subscribing on the spot`() {
        var opened: PodcastSearchResult? = null
        val row = result(1L, "Podlodka Podcast")
        setScreen(
            SearchUiState(query = "podlodka", results = listOf(row)),
            onOpenPreview = { opened = it },
        )

        composeRule.onNodeWithText("Podlodka Podcast").performClick()

        assertEquals(row, opened)
    }

    @Test
    fun `an apple exclusive explains itself instead of failing after the tap`() {
        var added: PodcastSearchResult? = null
        setScreen(
            SearchUiState(
                query = "exclusive",
                results = listOf(result(2L, "Exclusive Show", feedUrl = null)),
            ),
            onOpenPreview = { added = it },
        )

        composeRule.onNodeWithText("Apple Podcasts exclusive — no RSS feed to download").assertExists()
        composeRule.onNodeWithText("Exclusive Show").performClick()

        assertEquals(null, added)
    }

    @Test
    fun `a row being added shows a spinner and cannot be tapped again`() {
        var adds = 0
        val row = result(1L, "Podlodka Podcast")
        setScreen(
            SearchUiState(query = "podlodka", results = listOf(row), addingId = "1"),
            onOpenPreview = { adds++ },
        )

        composeRule.onNodeWithContentDescription("Add Podlodka Podcast").assertDoesNotExist()
        composeRule.onNodeWithText("Podlodka Podcast").performClick()

        assertEquals(0, adds)
    }

    /**
     * The row's second state: once the show is in the library there is nothing left to add, so the
     * tap has to mean something else. It opens the show, and says so to TalkBack rather than
     * leaving a bare tick to be interpreted.
     */
    @Test
    fun `an added row opens the show instead of adding it again`() {
        var adds = 0
        var opened: String? = null
        val row = result(1L, "Podlodka Podcast")
        setScreen(
            SearchUiState(
                query = "podlodka",
                results = listOf(row),
                addedPodcastIds = mapOf(1L to "stored-1"),
            ),
            onOpenPreview = { adds++ },
            onOpenPodcast = { opened = it },
        )

        composeRule.onNodeWithContentDescription("Add Podlodka Podcast").assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription("Open Podlodka Podcast, already in your library")
            .assertExists()

        composeRule.onNodeWithText("Podlodka Podcast").performClick()

        assertEquals("stored-1", opened)
        assertEquals(0, adds)
    }

    @Test
    fun `an added apple exclusive is still reachable`() {
        var opened: String? = null
        setScreen(
            SearchUiState(
                query = "exclusive",
                results = listOf(result(2L, "Exclusive Show", feedUrl = null)),
                addedPodcastIds = mapOf(2L to "stored-2"),
            ),
            onOpenPodcast = { opened = it },
        )

        composeRule.onNodeWithText("Exclusive Show").performClick()

        assertEquals("stored-2", opened)
    }

    @Test
    fun `the search bar carries the way back`() {
        var backs = 0
        setScreen(SearchUiState(), onBack = { backs++ })

        composeRule.onNodeWithContentDescription("Back to the library").performClick()

        assertEquals(1, backs)
    }
}

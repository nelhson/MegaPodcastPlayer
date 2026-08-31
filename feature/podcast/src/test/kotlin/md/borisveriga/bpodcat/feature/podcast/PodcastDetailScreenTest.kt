package md.borisveriga.bpodcat.feature.podcast

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [PodcastDetailScreen].
 *
 * The filtering itself is pinned by [EpisodeFilterTest]; what this covers is the screen's side of
 * it — that a chip actually changes the list, and that a filter matching nothing offers the way
 * back rather than leaving an empty page. It also covers the two actions that moved: playing the
 * newest episode is now a button on the header, and removing the show is behind an overflow
 * instead of sitting one mis-tap from the back arrow.
 *
 * Reordering is the third thing here, and what is worth pinning is which shows offer it at all: a
 * YouTube playlist is arranged by hand, an RSS feed is a chronology, and offering to rearrange the
 * latter would promise an order the next refresh could not keep.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PodcastDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val podcast = Podcast(
        id = "1",
        itunesId = null,
        title = "Podlodka Podcast",
        author = "Egor Tolstoy",
        feedUrl = "https://example.com/feed.rss",
        artworkUrl = null,
        description = "A weekly show about software and the people who build it.",
        addedAt = Instant.EPOCH,
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episode(
        id: String,
        positionMs: Long = 0L,
        isPlayed: Boolean = false,
        downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    ) = Episode(
        id = id,
        podcastId = "1",
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 5_025_000L,
        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
        sizeBytes = null,
        positionMs = positionMs,
        isPlayed = isPlayed,
        downloadState = downloadState,
    )

    private fun setScreen(
        episodes: List<Episode>,
        autoRefresh: Boolean = true,
        source: PodcastSource = PodcastSource.RSS,
        onEpisodeMove: (List<String>, Int, Int) -> Unit = { _, _, _ -> },
        onEpisodeClick: (String) -> Unit = {},
        onAutoRefreshChange: (Boolean) -> Unit = {},
        onRemove: () -> Unit = {},
    ) {
        composeRule.setContent {
            BPodcatTheme {
                PodcastDetailScreen(
                    uiState = PodcastDetailUiState(
                        podcast = podcast.copy(autoRefresh = autoRefresh, source = source),
                        episodes = episodes,
                        isLoading = false,
                    ),
                    onBack = {},
                    onEpisodeClick = onEpisodeClick,
                    onEpisodeDownloadToggle = {},
                    onEpisodeMove = onEpisodeMove,
                    onRefresh = {},
                    onAutoRefreshChange = onAutoRefreshChange,
                    onRemove = onRemove,
                    onMessageShown = {},
                )
            }
        }
    }

    @Test
    fun `a filter narrows the list to what it names`() {
        setScreen(
            listOf(
                episode("a"),
                episode("b", downloadState = DownloadState.COMPLETED),
            ),
        )

        composeRule.onNodeWithText("Episode a").assertExists()

        composeRule.onNodeWithText("Downloaded").performClick()

        composeRule.onNodeWithText("Episode b").assertExists()
        composeRule.onNodeWithText("Episode a").assertDoesNotExist()
    }

    @Test
    fun `a filter that matches nothing offers the full list back`() {
        setScreen(listOf(episode("a")))

        composeRule.onNodeWithText("In progress").performClick()

        composeRule.onNodeWithText("Episode a").assertDoesNotExist()
        composeRule.onNodeWithText("Nothing here").assertExists()

        composeRule.onNodeWithText("Show all episodes").performClick()

        composeRule.onNodeWithText("Episode a").assertExists()
    }

    @Test
    fun `an abandoned episode is in progress rather than unplayed`() {
        setScreen(listOf(episode("started", positionMs = 600_000L), episode("fresh")))

        composeRule.onNodeWithText("Unplayed").performClick()

        composeRule.onNodeWithText("Episode fresh").assertExists()
        composeRule.onNodeWithText("Episode started").assertDoesNotExist()
    }

    @Test
    fun `play latest starts the newest episode`() {
        var played: String? = null
        setScreen(
            listOf(episode("newest"), episode("older")),
            onEpisodeClick = { played = it },
        )

        composeRule.onNodeWithText("Play latest").performClick()

        assertEquals("newest", played)
    }

    @Test
    fun `removing the show is behind the overflow, not next to the back arrow`() {
        var removals = 0
        setScreen(listOf(episode("a")), onRemove = { removals++ })

        composeRule.onNodeWithText("Remove this podcast").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Remove this podcast").performClick()

        assertEquals(1, removals)
    }

    @Test
    fun `the overflow can turn background refresh off, which nothing could before`() {
        var enabled: Boolean? = null
        setScreen(listOf(episode("a")), autoRefresh = true, onAutoRefreshChange = { enabled = it })

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithContentDescription("Refreshing in the background").performClick()

        assertEquals(false, enabled)
    }

    @Test
    fun `the description can be opened out and closed again`() {
        setScreen(listOf(episode("a")))

        composeRule.onNodeWithText("Show more").performClick()
        composeRule.onNodeWithText("Show less").assertExists()

        composeRule.onNodeWithText("Show less").performClick()
        composeRule.onNodeWithText("Show more").assertExists()
    }

    @Test
    fun `a youtube show offers to move its videos`() {
        val moves = mutableListOf<Triple<List<String>, Int, Int>>()
        setScreen(
            episodes = listOf(episode("a"), episode("b"), episode("c")),
            source = PodcastSource.YOUTUBE,
            onEpisodeMove = { ids, from, to -> moves += Triple(ids, from, to) },
        )

        composeRule.onNodeWithText("Episode b").performCustomAccessibilityAction("Move up")

        // The visible ids travel with the positions: under a filter they are a subset, and the
        // positions alone would name the wrong videos.
        assertEquals(listOf(Triple(listOf("a", "b", "c"), 1, 0)), moves)
    }

    @Test
    fun `an rss show does not offer to move its episodes`() {
        setScreen(episodes = listOf(episode("a"), episode("b"), episode("c")))

        composeRule.onNodeWithText("Episode b")
            .assertHasNoCustomAccessibilityAction("Move up")
        composeRule.onNodeWithText("Episode b")
            .assertHasNoCustomAccessibilityAction("Move down")
    }
}

/**
 * Invokes a custom accessibility action by its label.
 *
 * Compose offers no matcher for this, and on a hand-ordered show these actions are the only way to
 * rearrange it with TalkBack on.
 */
private fun SemanticsNodeInteraction.performCustomAccessibilityAction(label: String) {
    val actions = fetchSemanticsNode().config[SemanticsActions.CustomActions]
    actions.first { it.label == label }.action()
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

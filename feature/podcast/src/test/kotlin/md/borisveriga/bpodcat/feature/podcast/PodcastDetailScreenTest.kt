package md.borisveriga.bpodcat.feature.podcast

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * latter would promise an order the next refresh could not keep. That line has to hold for the
 * long press as much as for the accessibility actions, and the press is the harder half: it is the
 * one gesture that leaves nothing on screen to say whether it applies, and a row that lifts under
 * the finger and then refuses to go anywhere is worse than a row that never lifts.
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
        source: PodcastSource = PodcastSource.RSS,
        onEpisodeMove: (List<String>, Int, Int) -> Unit = { _, _, _ -> },
        onEpisodeClick: (String) -> Unit = {},
        onRebuild: () -> Unit = {},
        onRemove: () -> Unit = {},
        onEpisodeDownloadToggle: (String) -> Unit = {},
        isRebuilding: Boolean = false,
    ) {
        composeRule.setContent {
            BPodcatTheme {
                PodcastDetailScreen(
                    uiState = PodcastDetailUiState(
                        podcast = podcast.copy(source = source),
                        episodes = episodes,
                        isLoading = false,
                        isRebuilding = isRebuilding,
                    ),
                    onBack = {},
                    onEpisodeClick = onEpisodeClick,
                    onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                    onEpisodeMove = onEpisodeMove,
                    onRefresh = {},
                    onRebuild = onRebuild,
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

    /**
     * The toggle was a setting among actions, and it sat directly above the two entries that
     * delete things. What a show does in the background now follows whatever it was added with.
     */
    @Test
    fun `the overflow offers no background-refresh toggle`() {
        setScreen(listOf(episode("a")))

        composeRule.onNodeWithContentDescription("More actions").performClick()

        composeRule.onNodeWithText("Refreshing in the background").assertDoesNotExist()
        composeRule.onNodeWithText("Not refreshing in the background").assertDoesNotExist()
    }

    @Test
    fun `rebuilding the list is behind the overflow and asks before it deletes`() {
        var rebuilds = 0
        setScreen(listOf(episode("a")), onRebuild = { rebuilds++ })

        composeRule.onNodeWithText("Delete and reload all episodes").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Delete and reload all episodes").performClick()

        // The menu item opens the question; it must not be the answer.
        assertEquals(0, rebuilds)
        composeRule.onNodeWithText("Delete 1 episode and reload?").assertExists()

        composeRule.onNodeWithText("Delete and reload").performClick()

        assertEquals(1, rebuilds)
    }

    @Test
    fun `cancelling the confirmation deletes nothing`() {
        var rebuilds = 0
        setScreen(listOf(episode("a")), onRebuild = { rebuilds++ })

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Delete and reload all episodes").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, rebuilds)
        composeRule.onNodeWithText("Delete 1 episode and reload?").assertDoesNotExist()
    }

    @Test
    fun `the confirmation counts what the rebuild would actually cost`() {
        setScreen(
            listOf(
                episode("untouched"),
                episode("started", positionMs = 600_000L),
                episode("stored", downloadState = DownloadState.COMPLETED),
                // Played but never downloaded: a mark that costs one tap to set again, which is
                // not worth inflating the warning with.
                episode("finished", isPlayed = true),
            ),
        )

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Delete and reload all episodes").performClick()

        composeRule.onNodeWithText("Delete 4 episodes and reload?").assertExists()
        composeRule
            .onNodeWithText("2 episodes lose their place and any audio saved on this device.")
            .assertExists()
    }

    @Test
    fun `a show with nothing to lose is not asked to confirm`() {
        var rebuilds = 0
        setScreen(episodes = emptyList(), onRebuild = { rebuilds++ })

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Delete and reload all episodes").performClick()

        // Nothing stored means nothing the confirmation could protect, and a dialog that only ever
        // says "delete these zero episodes?" teaches people to dismiss the one that matters.
        assertEquals(1, rebuilds)
    }

    @Test
    fun `a rebuild in flight leaves the list readable and says what it is doing`() {
        setScreen(listOf(episode("a")), isRebuilding = true)

        // The point of announcing it separately from an ordinary refresh: this one is about to
        // replace the very rows still on screen.
        composeRule.onNodeWithContentDescription("Reloading every episode").assertExists()
        composeRule.onNodeWithContentDescription("Checking for new episodes").assertDoesNotExist()
        composeRule.onNodeWithText("Episode a").assertExists()
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
    fun `a long press on a video picks it up, and the drag moves it`() {
        val moves = mutableListOf<Triple<List<String>, Int, Int>>()
        setScreen(
            episodes = listOf(episode("a"), episode("b"), episode("c")),
            source = PodcastSource.YOUTUBE,
            onEpisodeMove = { ids, from, to -> moves += Triple(ids, from, to) },
        )

        composeRule.onNodeWithText("Episode b").performTouchInput {
            down(center)
            // Held past the system's long-press timeout, which is what separates picking the row
            // up from tapping it or scrolling the list.
            advanceEventTime(LONG_PRESS_MS)
            // One row up puts the dragged row's centre inside the row above it.
            moveBy(Offset(0f, -height.toFloat()))
            up()
        }

        assertEquals(listOf(Triple(listOf("a", "b", "c"), 1, 0)), moves)
    }

    @Test
    fun `the same long press on an rss episode moves nothing`() {
        val moves = mutableListOf<Triple<List<String>, Int, Int>>()
        setScreen(
            episodes = listOf(episode("a"), episode("b"), episode("c")),
            onEpisodeMove = { ids, from, to -> moves += Triple(ids, from, to) },
        )

        composeRule.onNodeWithText("Episode b").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            moveBy(Offset(0f, -height.toFloat()))
            up()
        }

        assertEquals(emptyList<Triple<List<String>, Int, Int>>(), moves)
    }

    @Test
    fun `a row's swipe downloads it, and no button on the right does it any more`() {
        val toggled = mutableListOf<String>()
        setScreen(
            episodes = listOf(episode("a")),
            onEpisodeDownloadToggle = { toggled += it },
        )

        // The button that used to carry this is gone; the row itself carries it now.
        composeRule.onNodeWithContentDescription("Download").assertDoesNotExist()

        composeRule.onNodeWithText("Episode a").performCustomAccessibilityAction("Download")

        assertEquals(listOf("a"), toggled)
    }

    @Test
    fun `the swipe says what it will do, which follows the state the copy is in`() {
        setScreen(
            episodes = listOf(
                episode("stored", downloadState = DownloadState.COMPLETED),
                episode("busy", downloadState = DownloadState.DOWNLOADING),
                episode("broken", downloadState = DownloadState.FAILED),
            ),
        )

        // Deleting audio, calling a transfer off and trying a failure again are three different
        // promises, and the gesture that makes them is the same one — so the label is all the
        // user, or a screen reader, has to tell them apart.
        composeRule.onNodeWithText("Episode stored")
            .performCustomAccessibilityAction("Remove download")
        composeRule.onNodeWithText("Episode busy")
            .performCustomAccessibilityAction("Cancel download")
        composeRule.onNodeWithText("Episode broken")
            .performCustomAccessibilityAction("Download")
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

/** Comfortably past the 500ms system long-press timeout the drag gesture waits out. */
private const val LONG_PRESS_MS = 1_000L

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

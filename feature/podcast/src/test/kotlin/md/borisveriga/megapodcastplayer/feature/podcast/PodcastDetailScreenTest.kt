package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import md.borisveriga.megapodcastplayer.core.model.EpisodeSort
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * The row's two swipe tiers are the fourth: the pull downloads, and the button it reveals queues.
 * Neither is visible to a screen reader as a gesture, so both are published as actions on the row,
 * and those actions are what these tests reach for.
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
        description = "<p>What was said, with <a href=\"https://example.com\">a link</a>.</p>",
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
        onEpisodePlayNext: (String) -> Unit = {},
        onEpisodeSetPlayed: (String, Boolean) -> Unit = { _, _ -> },
        onEpisodePlay: (String) -> Unit = {},
        openEpisodeId: String? = null,
        isRebuilding: Boolean = false,
        settings: ShowSettings = ShowSettings.DEFAULT,
        onFilterChange: (EpisodeFilter) -> Unit = {},
        onSortChange: (EpisodeSort) -> Unit = {},
        description: String = podcast.description,
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                PodcastDetailScreen(
                    uiState = PodcastDetailUiState(
                        podcast = podcast.copy(source = source, description = description),
                        episodes = episodes,
                        isLoading = false,
                        isRebuilding = isRebuilding,
                        openEpisodeId = openEpisodeId,
                        settings = settings,
                    ),
                    onBack = {},
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlay = onEpisodePlay,
                    onEpisodePlayFrom = { _, _ -> },
                    onEpisodeAddToQueue = {},
                    onEpisodeSheetDismiss = {},
                    onEpisodeDownloadToggle = onEpisodeDownloadToggle,
                    onEpisodePlayNext = onEpisodePlayNext,
                    onEpisodeSetPlayed = onEpisodeSetPlayed,
                    onUndoPlayedChange = {},
                    onEpisodeMove = onEpisodeMove,
                    onFilterChange = onFilterChange,
                    onSortChange = onSortChange,
                    onShowSettingsChange = {},
                    onRefresh = {},
                    onRebuild = onRebuild,
                    onRemove = onRemove,
                    onExportDownloads = {},
                    onExportDownloadList = {},
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
            settings = ShowSettings(episodeFilter = EpisodeFilter.DOWNLOADED),
        )

        composeRule.onNodeWithText("Episode b").assertExists()
        composeRule.onNodeWithText("Episode a").assertDoesNotExist()
    }

    @Test
    fun `picking a filter reports it rather than keeping it`() {
        // The choice is the show's now, not the screen's: it is stored and comes back as state.
        // What the chip does here is ask for it.
        var chosen: EpisodeFilter? = null
        setScreen(listOf(episode("a")), onFilterChange = { chosen = it })

        composeRule.onNodeWithText("Downloaded").performClick()

        assertEquals(EpisodeFilter.DOWNLOADED, chosen)
    }

    @Test
    fun `a filter that matches nothing offers the full list back`() {
        var chosen: EpisodeFilter? = null
        setScreen(
            listOf(episode("a")),
            settings = ShowSettings(episodeFilter = EpisodeFilter.IN_PROGRESS),
            onFilterChange = { chosen = it },
        )

        composeRule.onNodeWithText("Episode a").assertDoesNotExist()
        composeRule.onNodeWithText("Nothing here").assertExists()

        composeRule.onNodeWithText("Show all episodes").performClick()

        assertEquals(EpisodeFilter.ALL, chosen)
    }

    @Test
    fun `oldest first turns the list over`() {
        setScreen(
            listOf(episode("newest"), episode("oldest")),
            settings = ShowSettings(episodeSort = EpisodeSort.OLDEST_FIRST),
        )

        val first = composeRule.onNodeWithText("Episode oldest").getBoundsInRoot()
        val second = composeRule.onNodeWithText("Episode newest").getBoundsInRoot()

        assertTrue(
            "Expected the oldest episode above the newest, got $first and $second",
            first.top < second.top,
        )
    }

    @Test
    fun `the order toggle asks for the other order`() {
        var chosen: EpisodeSort? = null
        setScreen(listOf(episode("a")), onSortChange = { chosen = it })

        composeRule.onNodeWithText("Newest first").performClick()

        assertEquals(EpisodeSort.OLDEST_FIRST, chosen)
    }

    @Test
    fun `a hand-arranged show is not offered an order`() {
        // A YouTube playlist is dragged into shape. Offering to reverse it would leave the drag
        // computing positions against an order nobody can see.
        setScreen(listOf(episode("a")), source = PodcastSource.YOUTUBE)

        composeRule.onNodeWithText("Newest first").assertDoesNotExist()
        composeRule.onNodeWithText("Oldest first").assertDoesNotExist()
    }

    @Test
    fun `the header button starts the newest unplayed episode`() {
        var played: String? = null
        setScreen(
            listOf(episode("newest"), episode("older")),
            onEpisodePlay = { played = it },
        )

        composeRule.onNodeWithText("Play newest").performClick()

        assertEquals("newest", played)
    }

    @Test
    fun `the header button continues an episode already in progress`() {
        // The commonest reason to open a show's page, and the one thing the button could not do:
        // it played the newest episode from wherever its position happened to be.
        var played: String? = null
        setScreen(
            listOf(episode("newest"), episode("started", positionMs = 1_800_000L)),
            onEpisodePlay = { played = it },
        )

        composeRule.onNodeWithText("Continue · 53 min left").performClick()

        assertEquals("started", played)
    }

    @Test
    fun `a show that is fully caught up says it is offering a replay`() {
        // Silently restarting the newest episode reads as the app having forgotten you heard it.
        var played: String? = null
        setScreen(
            listOf(episode("newest", isPlayed = true), episode("older", isPlayed = true)),
            onEpisodePlay = { played = it },
        )

        composeRule.onNodeWithText("Play newest again").performClick()

        assertEquals("newest", played)
    }

    @Test
    fun `marking an episode played is the sheet's, not the row's`() {
        var marked: Pair<String, Boolean>? = null
        setScreen(
            listOf(episode("a")),
            openEpisodeId = "a",
            onEpisodeSetPlayed = { id, played -> marked = id to played },
        )

        composeRule.onNodeWithText("Mark played").performClick()

        assertEquals("a" to true, marked)
    }

    @Test
    fun `the row's swipe no longer offers the mark`() {
        setScreen(listOf(episode("a")))

        // The swipe is invisible to a test as it is to a screen reader, so the row's spoken
        // actions are what says which of them it still carries: queueing, and the offline copy.
        composeRule.onNodeWithText("Episode a")
            .assertHasNoCustomAccessibilityAction("Mark played")
    }

    @Test
    fun `tapping a row opens the episode rather than playing it`() {
        var opened: String? = null
        var played: String? = null
        setScreen(
            listOf(episode("a")),
            onEpisodeClick = { opened = it },
            onEpisodePlay = { played = it },
        )

        composeRule.onNodeWithText("Episode a").performClick()

        // The change D-1 asked for: an episode can be read before it is heard.
        assertEquals("a", opened)
        assertNull(played)
    }

    @Test
    fun `the row's own button is what plays it, in one tap`() {
        var played: String? = null
        setScreen(listOf(episode("a")), onEpisodePlay = { played = it })

        composeRule.onNodeWithContentDescription("Play").performClick()

        assertEquals("a", played)
    }

    @Test
    fun `the open episode's notes and chapters are on the sheet`() {
        setScreen(listOf(episode("a")), openEpisodeId = "a")

        composeRule.onNodeWithText("Show notes").assertExists()
    }

    @Test
    fun `no sheet is drawn when no episode is open`() {
        setScreen(listOf(episode("a")))

        composeRule.onNodeWithText("Show notes").assertDoesNotExist()
    }

    @Test
    fun `a played episode offers to be marked unplayed instead`() {
        var marked: Pair<String, Boolean>? = null
        setScreen(
            listOf(episode("a", isPlayed = true)),
            openEpisodeId = "a",
            onEpisodeSetPlayed = { id, played -> marked = id to played },
        )

        composeRule.onNodeWithText("Mark unplayed").performClick()

        assertEquals("a" to false, marked)
    }

    @Test
    fun `removing the show is behind the overflow, not next to the back arrow`() {
        var removals = 0
        setScreen(listOf(episode("a")), onRemove = { removals++ })

        // COPY-2: a show leaves the *library*, so it is removed. It read "Delete" here and
        // "Remove" on the library row, for the same show and the same consequence.
        composeRule.onNodeWithText("Remove show").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Remove show").performClick()

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
    fun `the pull rebuilds the list and asks before it deletes`() {
        var rebuilds = 0
        setScreen(listOf(episode("a", positionMs = 600_000L)), onRebuild = { rebuilds++ })

        // It left the menu: a gesture is what a reader reaches for when a show's page looks wrong.
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Delete and reload all episodes").assertDoesNotExist()
        composeRule.onNodeWithText("Remove show").performClick()

        composeRule.pullDown()

        // The pull opens the question; it must not be the answer.
        assertEquals(0, rebuilds)
        composeRule.onNodeWithText("Delete 1 episode and reload?").assertExists()

        composeRule.onNodeWithText("Delete and reload").performClick()

        assertEquals(1, rebuilds)
    }

    @Test
    fun `cancelling the confirmation deletes nothing`() {
        var rebuilds = 0
        setScreen(listOf(episode("a", positionMs = 600_000L)), onRebuild = { rebuilds++ })

        composeRule.pullDown()
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

        composeRule.pullDown()

        composeRule.onNodeWithText("Delete 4 episodes and reload?").assertExists()
        composeRule
            .onNodeWithText("2 episodes lose their place and any audio saved on this device.")
            .assertExists()
    }

    @Test
    fun `a show with nothing to lose is not asked to confirm`() {
        var rebuilds = 0
        setScreen(listOf(episode("a")), onRebuild = { rebuilds++ })

        composeRule.pullDown()

        // Nothing stored means nothing the confirmation could protect, and a dialog that only ever
        // says "these episodes will be fetched again" teaches people to dismiss the one that
        // matters.
        assertEquals(1, rebuilds)
        composeRule.onNodeWithText("Delete 1 episode and reload?").assertDoesNotExist()
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
        setScreen(listOf(episode("a")), description = LONG_DESCRIPTION)

        composeRule.onNodeWithText("Show more").performClick()
        composeRule.onNodeWithText("Show less").assertExists()

        composeRule.onNodeWithText("Show less").performClick()
        composeRule.onNodeWithText("Show more").assertExists()
    }

    /**
     * SHOW-7. The button used to be drawn under every description, including the one-line ones it
     * had nothing to expand — a control that answers "show more of what?" with the same four lines
     * again. Whether the text was clipped is a question only the layout can answer, so it is asked
     * of the layout.
     */
    @Test
    fun `a description that fits is not offered a way to expand`() {
        setScreen(listOf(episode("a")))

        composeRule.onNodeWithText("A weekly show about software and the people who build it.")
            .assertExists()
        composeRule.onNodeWithText("Show more").assertDoesNotExist()
    }

    /**
     * The two facts the library's row has carried since it was written, on the page that is
     * actually about the show. Downloaded is named only when there is something downloaded: a
     * "0 downloaded" is a fact nobody asked for.
     */
    @Test
    fun `the header counts the episodes, and the copies`() {
        setScreen(
            listOf(
                episode("a", downloadState = DownloadState.COMPLETED),
                episode("b"),
                episode("c"),
            ),
        )

        composeRule.onNodeWithText("3 episodes · 1 downloaded").assertExists()
    }

    @Test
    fun `a show with nothing downloaded says only how many episodes it has`() {
        setScreen(listOf(episode("a"), episode("b")))

        composeRule.onNodeWithText("2 episodes").assertExists()
    }

    /** A playlist has videos, which is what the user called them when they added it. */
    @Test
    fun `a youtube playlist counts videos`() {
        setScreen(listOf(episode("a"), episode("b")), source = PodcastSource.YOUTUBE)

        composeRule.onNodeWithText("2 videos").assertExists()
    }

    /**
     * SHOW-7's other half. A show's page could hand out an episode and could not hand out the
     * show, so a reader who wanted it in another app had to go and find it again.
     */
    @Test
    fun `the overflow offers to share the show and to copy its feed`() {
        setScreen(listOf(episode("a")))

        composeRule.onNodeWithContentDescription("More actions").performClick()

        composeRule.onNodeWithText("Share show").assertExists()
        composeRule.onNodeWithText("Copy feed link").assertExists()
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
            .performCustomAccessibilityAction("Delete download")
        composeRule.onNodeWithText("Episode busy")
            .performCustomAccessibilityAction("Cancel download")
        composeRule.onNodeWithText("Episode broken")
            .performCustomAccessibilityAction("Download")
    }

    @Test
    fun `a row also offers to play an episode next, without interrupting what is playing`() {
        val queued = mutableListOf<String>()
        setScreen(
            episodes = listOf(episode("a")),
            onEpisodePlayNext = { queued += it },
        )

        composeRule.onNodeWithText("Episode a").performCustomAccessibilityAction("Play next")

        assertEquals(listOf("a"), queued)
    }

    @Test
    fun `queueing is offered whatever the offline copy is doing`() {
        // The two tiers are independent: the pull's meaning follows the download state, and the
        // button behind it does not. A downloaded episode that could no longer be queued would be
        // the odd one out for no reason the user could see.
        setScreen(
            episodes = listOf(
                episode("stored", downloadState = DownloadState.COMPLETED),
                episode("busy", downloadState = DownloadState.DOWNLOADING),
            ),
        )

        composeRule.onNodeWithText("Episode stored")
            .performCustomAccessibilityAction("Play next")
        composeRule.onNodeWithText("Episode busy")
            .performCustomAccessibilityAction("Play next")
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
 * Pulls the list down far enough to pass the refresh threshold.
 *
 * On the root rather than on a row: the gesture is the whole list's, and a single row is not tall
 * enough on its own to carry a swipe past the distance the indicator asks for. It starts at the
 * middle so that the finger lands on the list rather than on the top bar, which is outside the
 * box that reads the pull.
 */
private fun ComposeContentTestRule.pullDown() {
    onRoot().performTouchInput {
        swipeDown(startY = centerY, endY = bottom - 1f, durationMillis = PULL_MS)
    }
    waitForIdle()
}

/** Long enough that the pull reads as a drag rather than a fling that outruns the threshold. */
private const val PULL_MS = 500L

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

/**
 * A description that cannot fit in four lines however it is measured.
 *
 * Six hard line breaks rather than one long paragraph, and that is not fussiness: a Robolectric
 * text layout is not the device's, so a paragraph that wraps to eight lines on the Fold may wrap to
 * one here, and a test that turned on the wrapping would be testing the test runner's font. Lines
 * the publisher wrote are lines whatever measures them.
 */
private val LONG_DESCRIPTION = """
    A weekly show about software.
    And about the people who build it.
    Recorded in front of nobody in particular.
    Published on Tuesdays, mostly.
    Occasionally on Wednesdays.
    Never on a Friday.
""".trimIndent()

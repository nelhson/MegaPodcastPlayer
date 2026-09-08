package md.borisveriga.megapodcastplayer.feature.moments

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode
import md.borisveriga.megapodcastplayer.core.model.MomentsFilter
import md.borisveriga.megapodcastplayer.core.model.showsWithMoments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [MomentsScreen].
 *
 * Two things here are worth pinning and neither is visible in a preview. A row leads with the note
 * when there is one and with the episode when there is not — get that the wrong way round and every
 * row of a well-annotated list says the same four words — and the export action must be refused
 * while there is nothing to export, since a picker that creates a file and then writes nothing to
 * it is worse than a disabled button.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MomentsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(
        id: Long,
        note: String? = null,
        showTitle: String = "Podlodka Podcast",
        feedUrl: String = "https://feeds.simplecast.com/podlodka",
    ) = MomentWithEpisode(
        moment = Moment(
            id = id,
            episodeId = "episode-$id",
            positionMs = 743_000L,
            note = note,
            createdAtMs = 1_000L,
        ),
        episodeTitle = "Episode $id",
        showTitle = showTitle,
        showArtworkUrl = null,
        feedUrl = feedUrl,
        audioUrl = "https://cdn.example.com/$id.mp3",
    )

    private fun setContent(
        uiState: MomentsUiState,
        onPlay: (MomentWithEpisode) -> Unit = {},
        onShare: (MomentWithEpisode) -> Unit = {},
        onEdit: (MomentWithEpisode) -> Unit = {},
        onDelete: (MomentWithEpisode) -> Unit = {},
        onExport: () -> Unit = {},
        onQueryChange: (String) -> Unit = {},
        onShowChange: (String?) -> Unit = {},
        onGroupByShowChange: (Boolean) -> Unit = {},
        onOpenSettings: () -> Unit = {},
        scrollToTopSignal: Int = 0,
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                MomentsScreen(
                    uiState = uiState,
                    onPlay = onPlay,
                    onShare = onShare,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onExport = onExport,
                    onQueryChange = onQueryChange,
                    onShowChange = onShowChange,
                    onGroupByShowChange = onGroupByShowChange,
                    onSaveNote = {},
                    onCancelEdit = {},
                    onUndoDelete = {},
                    onMessageShown = {},
                    onOpenSettings = onOpenSettings,
                    scrollToTopSignal = scrollToTopSignal,
                )
            }
        }
    }

    /** Nine moments across two shows: past the threshold the narrowing controls appear at. */
    private val manyMoments = (1L..5L).map { entry(it, note = "Note $it") } +
        (6L..9L).map { entry(it, showTitle = "Radio-T", feedUrl = "https://radio-t.com/rss") }

    @Test
    fun `an empty list explains where moments come from`() {
        setContent(MomentsUiState(isLoading = false))

        composeRule.onNodeWithText("No moments yet").assertIsDisplayed()
    }

    @Test
    fun `a row with a note leads with it, and still names the episode`() {
        setContent(MomentsUiState(isLoading = false, moments = listOf(entry(1L, note = "the good bit"))))

        composeRule.onNodeWithText("the good bit").assertIsDisplayed()
        composeRule.onNodeWithText("Episode 1").assertIsDisplayed()
        composeRule.onNodeWithText("Podlodka Podcast · 12:23").assertIsDisplayed()
    }

    @Test
    fun `a row without a note leads with the episode`() {
        setContent(MomentsUiState(isLoading = false, moments = listOf(entry(1L))))

        composeRule.onNodeWithText("Episode 1").assertIsDisplayed()
    }

    @Test
    fun `tapping a row plays it`() {
        var played: MomentWithEpisode? = null
        setContent(
            MomentsUiState(isLoading = false, moments = listOf(entry(1L, note = "the good bit"))),
            onPlay = { played = it },
        )

        composeRule.onNodeWithText("the good bit").performClick()

        assertEquals(1L, played?.moment?.id)
    }

    @Test
    fun `sharing is reached from the row's own menu`() {
        var shared: MomentWithEpisode? = null
        setContent(
            MomentsUiState(isLoading = false, moments = listOf(entry(1L))),
            onShare = { shared = it },
        )

        composeRule.onNodeWithContentDescription("More for this moment").performClick()
        composeRule.onNodeWithText("Share").performClick()

        assertEquals(1L, shared?.moment?.id)
    }

    @Test
    fun `deleting is reached the same way`() {
        var deleted: MomentWithEpisode? = null
        setContent(
            MomentsUiState(isLoading = false, moments = listOf(entry(1L))),
            onDelete = { deleted = it },
        )

        composeRule.onNodeWithContentDescription("More for this moment").performClick()
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(1L, deleted?.moment?.id)
    }

    @Test
    fun `the export action is refused while there is nothing to write`() {
        setContent(MomentsUiState(isLoading = false))

        composeRule.onNodeWithContentDescription("Export all, with links").assertIsNotEnabled()
    }

    @Test
    fun `the export action is offered once there is something to write`() {
        var exported = false
        setContent(
            MomentsUiState(isLoading = false, moments = listOf(entry(1L))),
            onExport = { exported = true },
        )

        composeRule.onNodeWithContentDescription("Export all, with links").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Export all, with links").performClick()

        assertEquals(true, exported)
    }

    @Test
    fun `a short list is not given controls it does not need`() {
        setContent(
            MomentsUiState(isLoading = false, moments = listOf(entry(1L)), savedCount = 1),
        )

        composeRule.onNodeWithText("Search notes and episodes").assertDoesNotExist()
        composeRule.onNodeWithText("Group by show").assertDoesNotExist()
    }

    @Test
    fun `a long list is given a search field, a show chip and a grouping chip`() {
        setContent(
            MomentsUiState(
                isLoading = false,
                moments = manyMoments,
                shows = manyMoments.showsWithMoments(),
                savedCount = manyMoments.size,
            ),
        )

        composeRule.onNodeWithText("Search notes and episodes").assertIsDisplayed()
        // "All shows" rather than a word like "Show": a filter chip should say what is in force,
        // and "every one of them" is an answer.
        composeRule.onNodeWithText("All shows").assertIsDisplayed()
        composeRule.onNodeWithText("Group by show").assertIsDisplayed()
    }

    @Test
    fun `typing reports what was typed`() {
        var typed: String? = null
        setContent(
            MomentsUiState(
                isLoading = false,
                moments = manyMoments,
                savedCount = manyMoments.size,
            ),
            onQueryChange = { typed = it },
        )

        composeRule.onNodeWithText("Search notes and episodes").performTextInput("radio")

        assertEquals("radio", typed)
    }

    @Test
    fun `the show menu lists the shows with their counts`() {
        setContent(
            MomentsUiState(
                isLoading = false,
                moments = manyMoments,
                shows = manyMoments.showsWithMoments(),
                savedCount = manyMoments.size,
            ),
        )

        composeRule.onNodeWithText("All shows").performClick()

        composeRule.onNodeWithText("Podlodka Podcast (5)").assertIsDisplayed()
        composeRule.onNodeWithText("Radio-T (4)").assertIsDisplayed()
    }

    @Test
    fun `the chip names the chosen show rather than the word Show`() {
        setContent(
            MomentsUiState(
                isLoading = false,
                moments = manyMoments,
                shows = manyMoments.showsWithMoments(),
                filter = MomentsFilter(feedUrl = "https://radio-t.com/rss"),
                savedCount = manyMoments.size,
            ),
        )

        composeRule.onAllNodesWithText("Radio-T").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("All shows").assertDoesNotExist()
    }

    @Test
    fun `toggling the grouping reports it`() {
        var grouped: Boolean? = null
        setContent(
            MomentsUiState(
                isLoading = false,
                moments = manyMoments,
                savedCount = manyMoments.size,
            ),
            onGroupByShowChange = { grouped = it },
        )

        composeRule.onNodeWithText("Group by show").performClick()

        assertEquals(true, grouped)
    }

    /**
     * The two empty states are different facts and want opposite things done about them, so a
     * filter that matches nothing must not say "no moments yet" to someone with ninety of them.
     */
    @Test
    fun `a filter that matches nothing says so rather than saying the library is empty`() {
        setContent(
            MomentsUiState(
                isLoading = false,
                moments = emptyList(),
                shows = manyMoments.showsWithMoments(),
                filter = MomentsFilter(query = "nothing"),
                savedCount = manyMoments.size,
            ),
        )

        composeRule.onNodeWithText("Nothing matches").assertIsDisplayed()
        composeRule.onNodeWithText("No moments yet").assertDoesNotExist()
    }

    /**
     * The swipe's two actions are also custom accessibility actions on the row, because a screen
     * reader can see no gesture. MOM-2 exists because editing a note took three taps through a
     * menu; for TalkBack it took the same three, and this is the shorter path for both.
     */
    @Test
    fun `the row offers editing and deleting without opening the menu`() {
        setContent(
            MomentsUiState(isLoading = false, moments = listOf(entry(1L)), savedCount = 1),
        )

        composeRule.onNodeWithText("Episode 1").assert(hasCustomAction("Edit note"))
        composeRule.onNodeWithText("Episode 1").assert(hasCustomAction("Delete"))
    }

    /**
     * Matches a node carrying a custom accessibility action with this label.
     *
     * @param label the action, as a screen reader would announce it.
     */
    private fun hasCustomAction(label: String) =
        SemanticsMatcher("has the custom action \"$label\"") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
                .any { action -> action.label == label }
        }

    /**
     * NAV-5: the gear is on every top-level bar, not the library's alone. From here it used to be a
     * tab switch plus a tap.
     */
    @Test
    fun `the bar carries the settings gear`() {
        var opened = false
        setContent(
            MomentsUiState(isLoading = false, moments = listOf(entry(1L))),
            onOpenSettings = { opened = true },
        )

        composeRule.onNodeWithContentDescription("Settings").performClick()

        assertTrue(opened)
    }
}

/*
 * The note editor is deliberately not driven from here. `NoteDialog` focuses its field on arrival,
 * and a focused text cursor blinks forever, which Robolectric reads as a composition that never
 * goes idle — the test times out after a minute without ever asserting anything. What the dialog
 * does with an existing note is covered by `MomentsViewModelTest`, which needs no composition.
 */

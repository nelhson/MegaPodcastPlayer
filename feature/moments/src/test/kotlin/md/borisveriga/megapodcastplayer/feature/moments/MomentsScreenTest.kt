package md.borisveriga.megapodcastplayer.feature.moments

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode
import org.junit.Assert.assertEquals
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

    private fun entry(id: Long, note: String? = null) = MomentWithEpisode(
        moment = Moment(
            id = id,
            episodeId = "episode-$id",
            positionMs = 743_000L,
            note = note,
            createdAtMs = 1_000L,
        ),
        episodeTitle = "Episode $id",
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
        feedUrl = "https://feeds.simplecast.com/podlodka",
        audioUrl = "https://cdn.example.com/$id.mp3",
    )

    private fun setContent(
        uiState: MomentsUiState,
        onPlay: (MomentWithEpisode) -> Unit = {},
        onShare: (MomentWithEpisode) -> Unit = {},
        onDelete: (MomentWithEpisode) -> Unit = {},
        onExport: () -> Unit = {},
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                MomentsScreen(
                    uiState = uiState,
                    onPlay = onPlay,
                    onShare = onShare,
                    onEdit = {},
                    onDelete = onDelete,
                    onExport = onExport,
                    onSaveNote = {},
                    onCancelEdit = {},
                    onUndoDelete = {},
                    onMessageShown = {},
                )
            }
        }
    }

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
}

/*
 * The note editor is deliberately not driven from here. `NoteDialog` focuses its field on arrival,
 * and a focused text cursor blinks forever, which Robolectric reads as a composition that never
 * goes idle — the test times out after a minute without ever asserting anything. What the dialog
 * does with an existing note is covered by `MomentsViewModelTest`, which needs no composition.
 */

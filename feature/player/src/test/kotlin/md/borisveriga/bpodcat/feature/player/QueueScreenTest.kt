package md.borisveriga.bpodcat.feature.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.media.PlayableEpisode
import md.borisveriga.bpodcat.core.media.PlaybackState
import md.borisveriga.bpodcat.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [QueueScreen].
 *
 * The reorder gesture itself is not exercised here — a synthetic drag over a `LazyColumn` proves
 * little that `ReorderableStateTest` in `:core:designsystem` does not prove directly. What is
 * worth pinning on the screen is the part a drag test would not reach anyway: that the queue is
 * editable *without* a gesture, via the custom accessibility actions, and that those actions
 * report positions in the "up next" list rather than including the episode that is playing. That
 * off-by-one is the whole reason [PlayerViewModel.moveInUpNext] exists.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class QueueScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun playable(id: String) = PlayableEpisode(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
            sizeBytes = null,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    private val playingFirst = PlayerUiState(
        playback = PlaybackState(
            isConnected = true,
            episodeId = "a",
            queueEpisodeIds = listOf("a", "b", "c"),
            queueIndex = 0,
        ),
        queue = listOf(playable("a"), playable("b"), playable("c")),
    )

    private fun setContent(
        uiState: PlayerUiState = playingFirst,
        onPlay: (String) -> Unit = {},
        onRemove: (String) -> Unit = {},
        onMove: (Int, Int) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            BPodcatTheme {
                QueueScreen(
                    uiState = uiState,
                    onBack = {},
                    onPlay = onPlay,
                    onRemove = onRemove,
                    onMove = onMove,
                )
            }
        }
    }

    @Test
    fun `the episode playing is shown above the ones that follow it`() {
        setContent()

        composeRule.onNodeWithText("Now playing").assertIsDisplayed()
        composeRule.onNodeWithText("Up next").assertIsDisplayed()
        composeRule.onNodeWithText("Episode a").assertIsDisplayed()
        composeRule.onNodeWithText("Episode b").assertIsDisplayed()
    }

    @Test
    fun `an empty queue says so rather than showing an empty list`() {
        setContent(uiState = PlayerUiState())

        composeRule.onNodeWithText("Nothing queued").assertIsDisplayed()
    }

    @Test
    fun `tapping a queued episode plays it`() {
        var played: String? = null
        setContent(onPlay = { played = it })

        composeRule.onNodeWithText("Episode b").performClick()

        assertEquals("b", played)
    }

    @Test
    fun `moving a row down reports positions within up next, not the whole queue`() {
        // "b" is the second episode in the queue but the *first* in "up next", because "a" is
        // playing. Reporting 1 here instead of 0 would move the wrong episode.
        var move: Pair<Int, Int>? = null
        setContent(onMove = { from, to -> move = from to to })

        composeRule.onNodeWithText("Episode b")
            .performCustomAccessibilityActionWithLabel("Move down")

        assertEquals(0 to 1, move)
    }

    @Test
    fun `the first queued episode cannot be moved up`() {
        setContent()

        // No "Move up" action is published for it, so asking for one fails rather than silently
        // doing nothing — which is what makes this assertion meaningful.
        val hasMoveUp = runCatching {
            composeRule.onNodeWithText("Episode b")
                .performCustomAccessibilityActionWithLabel("Move up")
        }.isSuccess

        assertEquals(false, hasMoveUp)
    }

    @Test
    fun `a queued episode can be removed without a swipe`() {
        var removed: String? = null
        setContent(onRemove = { removed = it })

        composeRule.onNodeWithText("Episode c")
            .performCustomAccessibilityActionWithLabel("Remove Episode c from the queue")

        assertEquals("c", removed)
    }
}

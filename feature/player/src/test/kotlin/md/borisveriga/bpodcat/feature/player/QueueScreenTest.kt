package md.borisveriga.bpodcat.feature.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
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
 * Most of what is worth pinning on the screen is the part a drag test would not reach anyway:
 * that the queue is editable *without* a gesture, via the custom accessibility actions, and that
 * those actions report positions in the "up next" list rather than including the episode that is
 * playing. That off-by-one is the whole reason [PlayerViewModel.moveInUpNext] exists.
 *
 * One real drag is driven all the same, because the row is the busiest in the app: a tap plays it,
 * a horizontal swipe removes it, and a long press picks it up. The arithmetic behind the drag
 * belongs to `ReorderableStateTest` in `:core:designsystem`; what this pins is that the long press
 * reaches it at all from under the swipe box, and still reports its positions in "up next".
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
        onMarkPlayed: (String) -> Unit = {},
        onUndo: () -> Unit = {},
        onMessageShown: () -> Unit = {},
    ) {
        composeRule.setContent {
            BPodcatTheme {
                QueueScreen(
                    uiState = uiState,
                    onPlay = onPlay,
                    onRemove = onRemove,
                    onMarkPlayed = onMarkPlayed,
                    onMove = onMove,
                    onUndo = onUndo,
                    onMessageShown = onMessageShown,
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
    fun `the screen is titled and carries no back arrow, because it is a tab`() {
        setContent()

        composeRule.onNodeWithText("Queue").assertIsDisplayed()
        // A back arrow here would offer to leave a top-level destination for whichever tab
        // happened to precede it. `BPodcatLargeTopAppBar` draws one only when given a handler,
        // and the queue no longer has one to give.
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
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
    fun `a long press anywhere on a row picks it up, and the drag reorders up next`() {
        var move: Pair<Int, Int>? = null
        setContent(onMove = { from, to -> move = from to to })

        composeRule.onNodeWithText("Episode b").performTouchInput {
            down(center)
            // Held past the system's long-press timeout, which is what separates picking the row
            // up from tapping it, swiping it away, or scrolling the queue.
            advanceEventTime(LONG_PRESS_MS)
            // One row down puts the dragged row's centre inside the row below it.
            moveBy(Offset(0f, height.toFloat()))
            up()
        }

        // "b" is the second episode in the queue but the first in "up next": a drag has to be
        // reported the same way the accessibility actions are.
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
            .performCustomAccessibilityActionWithLabel("Remove")

        assertEquals("c", removed)
    }

    @Test
    fun `a queued episode can be marked played without a swipe`() {
        var marked: String? = null
        setContent(onMarkPlayed = { marked = it })

        composeRule.onNodeWithText("Episode c")
            .performCustomAccessibilityActionWithLabel("Mark played")

        assertEquals("c", marked)
    }

    @Test
    fun `dragging a row past half its width removes it`() {
        var removed: String? = null
        var marked: String? = null
        setContent(onRemove = { removed = it }, onMarkPlayed = { marked = it })

        composeRule.onNodeWithText("Episode b").performTouchInput {
            down(centerRight)
            // Slowly and in steps: the commit tier answers to distance rather than velocity, and
            // one jump would be read as a fling and settled by the other rule.
            repeat(SWIPE_STEPS) {
                moveBy(Offset(-width / SWIPE_STEPS.toFloat(), 0f))
                advanceEventTime(SWIPE_STEP_MS)
            }
            up()
        }

        assertEquals("b", removed)
        assertEquals(null, marked)
    }

    @Test
    fun `a short swipe reveals mark played rather than removing the row`() {
        var removed: String? = null
        var marked: String? = null
        setContent(onRemove = { removed = it }, onMarkPlayed = { marked = it })

        composeRule.onNodeWithText("Episode b").performTouchInput {
            down(centerRight)
            // Far enough to rest open, nowhere near the commit threshold. The two tiers sharing one
            // gesture is the whole design, and a threshold set wrong would remove the row here.
            repeat(SWIPE_STEPS) {
                moveBy(Offset(-SHORT_SWIPE_PX / SWIPE_STEPS, 0f))
                advanceEventTime(SWIPE_STEP_MS)
            }
            up()
        }

        assertEquals(null, removed)
        composeRule.onNodeWithText("Mark played").performClick()
        assertEquals("b", marked)
    }

    @Test
    fun `the episode playing carries no swipe actions`() {
        setContent()

        // "Remove" and "mark played" both mean "stop playing this" for the current episode, which
        // is what the player's own controls are for. Finding either here by accident would stop
        // the audio with no warning.
        val hasRemove = runCatching {
            composeRule.onNodeWithText("Episode a")
                .performCustomAccessibilityActionWithLabel("Remove")
        }.isSuccess

        assertEquals(false, hasRemove)
    }

    @Test
    fun `a removal is offered back, and taking the offer undoes it`() {
        var undone = 0
        setContent(
            uiState = playingFirst.copy(message = QueueMessage.Removed("Episode b")),
            onUndo = { undone++ },
        )

        composeRule.onNodeWithText("Removed Episode b").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()

        assertEquals(1, undone)
    }

    private companion object {
        /** Comfortably past the 500ms system long-press timeout the drag gesture waits out. */
        const val LONG_PRESS_MS = 1_000L

        /** How many steps a driven swipe is broken into, so it reads as a drag rather than a fling. */
        const val SWIPE_STEPS = 10

        /** Milliseconds between those steps; slow enough to stay under the fling velocity. */
        const val SWIPE_STEP_MS = 32L

        /**
         * Far enough to rest the row open, nowhere near half its width.
         *
         * Has to clear two floors, not one: Compose swallows a touch slop's worth of the first
         * movement, and what is left must still pass half the revealed button's width. On the
         * 411dp xxhdpi device this class configures, half the row — the commit threshold — is
         * around 616px, so this sits comfortably between the two.
         */
        const val SHORT_SWIPE_PX = 300f
    }
}

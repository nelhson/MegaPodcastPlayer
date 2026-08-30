package md.borisveriga.bpodcat.feature.player

import androidx.compose.foundation.lazy.LazyListState
import java.time.Instant
import md.borisveriga.bpodcat.core.media.PlayableEpisode
import md.borisveriga.bpodcat.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [QueueDragState].
 *
 * The gesture arithmetic — which row the finger is over — needs a laid-out list and is left to the
 * emulator. What is testable here is everything around it, and it is the part that would go wrong
 * silently: that a drag is reported once rather than per frame, that a drag ending where it began
 * reports nothing, and that an emission from the player is refused while a finger is down. That
 * last one is the failure the user would actually feel — the list snapping back mid-drag because
 * the media session echoed the old order.
 */
class QueueDragStateTest {

    private val moves = mutableListOf<Pair<Int, Int>>()
    private lateinit var state: QueueDragState

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

    private val queue = listOf(playable("a"), playable("b"), playable("c"))

    @Before
    fun setUp() {
        moves.clear()
        // The list state is never laid out, so `visibleItemsInfo` is empty: every code path here is
        // one that does not depend on it.
        state = QueueDragState(LazyListState()) { from, to -> moves += from to to }
        state.adopt(queue)
    }

    @Test
    fun `the drawn order starts as the player's`() {
        assertEquals(listOf("a", "b", "c"), state.order.map { it.episode.id })
    }

    @Test
    fun `a drag in progress keeps the player's emissions off the screen`() {
        state.onDragStart("a")

        state.adopt(listOf(playable("c"), playable("b"), playable("a")))

        // Adopting mid-drag would yank the list out from under the finger.
        assertEquals(listOf("a", "b", "c"), state.order.map { it.episode.id })
    }

    @Test
    fun `the player's order is picked up again once the finger lifts`() {
        state.onDragStart("a")
        state.onDragCancel()

        state.adopt(listOf(playable("c"), playable("b"), playable("a")))

        assertEquals(listOf("c", "b", "a"), state.order.map { it.episode.id })
    }

    @Test
    fun `following the finger does not report anything on its own`() {
        state.onDragStart("a")
        state.onDrag(20f)
        state.onDrag(20f)
        state.onDrag(20f)

        // One gesture is one edit. Reporting per frame would have the player and the database
        // renegotiating the queue dozens of times for a single drag.
        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `a drag that ends where it began reports nothing`() {
        state.onDragStart("b")
        state.onDrag(5f)
        state.onDragEnd()

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `a cancelled drag reports nothing`() {
        state.onDragStart("b")
        state.onDragCancel()

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `grabbing an episode that is not in the queue starts nothing`() {
        state.onDragStart("gone")

        assertNull(state.draggingId)
    }

    @Test
    fun `an accessibility move reorders the list and reports it once`() {
        state.move(from = 0, to = 2)

        assertEquals(listOf("b", "c", "a"), state.order.map { it.episode.id })
        assertEquals(listOf(0 to 2), moves)
    }

    @Test
    fun `an accessibility move off the end of the list does nothing`() {
        state.move(from = 0, to = 3)

        assertEquals(listOf("a", "b", "c"), state.order.map { it.episode.id })
        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `an accessibility move onto the same position does nothing`() {
        state.move(from = 1, to = 1)

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }
}

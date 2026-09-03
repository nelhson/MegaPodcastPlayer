package md.borisveriga.megapodcastplayer.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [movedTo].
 *
 * Reorder logic is easy to write in a way that is right for one drag direction and wrong for the
 * other, and the wrong version still looks plausible on screen — it just quietly displaces a
 * different episode. Both directions are pinned here, along with the cases that must do nothing.
 */
class QueueReorderTest {

    private val queue = listOf("a", "b", "c", "d")

    @Test
    fun `dragging an episode up puts it where the target was`() {
        assertEquals(listOf("a", "d", "b", "c"), queue.movedTo(movedId = "d", targetId = "b"))
    }

    @Test
    fun `dragging an episode down puts it where the target was`() {
        assertEquals(listOf("b", "c", "a", "d"), queue.movedTo(movedId = "a", targetId = "c"))
    }

    @Test
    fun `a move is a reorder, not a swap`() {
        // The distinction only shows up over a distance: swapping would fling "b" to the bottom,
        // which is not what dragging the last episode to the top means.
        val result = queue.movedTo(movedId = "d", targetId = "a")

        assertEquals(listOf("d", "a", "b", "c"), result)
    }

    @Test
    fun `moving to the neighbouring slot swaps the two, which is the same rule`() {
        assertEquals(listOf("b", "a", "c", "d"), queue.movedTo(movedId = "a", targetId = "b"))
    }

    @Test
    fun `dropping an episode on itself changes nothing`() {
        assertNull(queue.movedTo(movedId = "b", targetId = "b"))
    }

    @Test
    fun `an episode that is not in the queue is refused rather than appended`() {
        // Fails closed on purpose: a queue that has drifted out of step with what was drawn should
        // do nothing, not reorder whichever episode happens to sit at that index now.
        assertNull(queue.movedTo(movedId = "gone", targetId = "b"))
        assertNull(queue.movedTo(movedId = "b", targetId = "gone"))
    }

    @Test
    fun `an empty queue has nothing to move`() {
        assertNull(emptyList<String>().movedTo(movedId = "a", targetId = "b"))
    }

    @Test
    fun `the queue is not mutated in place`() {
        val original = queue.toList()

        queue.movedTo(movedId = "a", targetId = "d")

        assertEquals(original, queue)
    }
}

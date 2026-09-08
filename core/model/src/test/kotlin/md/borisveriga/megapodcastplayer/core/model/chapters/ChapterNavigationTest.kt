package md.borisveriga.megapodcastplayer.core.model.chapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the chapter navigation helpers, which are pure so the player need not be involved. */
class ChapterNavigationTest {

    private val chapters = listOf(
        Chapter(startMs = 0L, title = "Intro"),
        Chapter(startMs = 60_000L, title = "Middle"),
        Chapter(startMs = 120_000L, title = "End"),
    )

    @Test
    fun `indexOfCurrent finds the chapter a position falls in`() {
        assertEquals(0, chapters.indexOfCurrent(0L))
        assertEquals(0, chapters.indexOfCurrent(59_999L))
        assertEquals(1, chapters.indexOfCurrent(60_000L))
        assertEquals(2, chapters.indexOfCurrent(600_000L))
    }

    @Test
    fun `indexOfCurrent reports nothing before the first chapter starts`() {
        val late = listOf(Chapter(startMs = 30_000L, title = "Late start"))

        assertEquals(-1, late.indexOfCurrent(0L))
    }

    @Test
    fun `nextStartAfter walks forwards`() {
        assertEquals(60_000L, chapters.nextStartAfter(0L))
        assertEquals(120_000L, chapters.nextStartAfter(60_000L))
    }

    @Test
    fun `nextStartAfter has nowhere to go from the last chapter`() {
        assertNull(chapters.nextStartAfter(120_000L))
        assertNull(chapters.nextStartAfter(600_000L))
    }

    @Test
    fun `previousStartBefore restarts a chapter that is already under way`() {
        // Four seconds in, past the three-second threshold: "back" means "play this again".
        assertEquals(60_000L, chapters.previousStartBefore(64_000L))
    }

    @Test
    fun `previousStartBefore steps back when pressed near a boundary`() {
        assertEquals(0L, chapters.previousStartBefore(61_000L))
    }

    @Test
    fun `previousStartBefore stays put at the first chapter`() {
        assertEquals(0L, chapters.previousStartBefore(1_000L))
        assertEquals(0L, chapters.previousStartBefore(30_000L))
    }

    @Test
    fun `previousStartBefore has nothing to offer before the first chapter starts`() {
        val late = listOf(Chapter(startMs = 30_000L, title = "Late start"))

        assertNull(late.previousStartBefore(0L))
    }

    @Test
    fun `an empty list navigates nowhere`() {
        assertEquals(-1, emptyList<Chapter>().indexOfCurrent(0L))
        assertNull(emptyList<Chapter>().nextStartAfter(0L))
        assertNull(emptyList<Chapter>().previousStartBefore(0L))
    }
}

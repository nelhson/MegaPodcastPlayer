package md.borisveriga.megapodcastplayer.core.model.chapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [chaptersFromDescription].
 *
 * The rejections matter more than the acceptances. A wrong chapter list is worse than none at all,
 * because it silently misplaces every seek the user makes from it — so most of what follows is
 * prose that contains a timestamp and must not be read as a list.
 */
class DescriptionChaptersTest {

    /** A real YouTube description, of the shape this exists to read. */
    private val youTubeDescription = """
        In this episode we talk about Kotlin Multiplatform and where it actually pays off.

        0:00 Intro
        2:15 What KMP is for
        9:40 The iOS side
        21:03 Compose Multiplatform
        35:18 Build times
        48:52 Testing across targets
        1:02:33 What we would do differently
        1:14:07 Outro

        Links in the comments.
    """.trimIndent()

    @Test
    fun `reads a youtube timestamp block`() {
        val chapters = chaptersFromDescription(youTubeDescription, durationMs = 4_600_000L)

        assertEquals(8, chapters.size)
        assertEquals(0L, chapters.first().startMs)
        assertEquals("Intro", chapters.first().title)
        assertEquals(3_753_000L, chapters[6].startMs)
        assertEquals("What we would do differently", chapters[6].title)
    }

    @Test
    fun `reads a list written with dashes and brackets`() {
        val text = """
            - [0:00] Cold open
            - [4:32] The interview
            - [58:10] Wrap up
        """.trimIndent()

        val chapters = chaptersFromDescription(text, durationMs = null)

        assertEquals(listOf("Cold open", "The interview", "Wrap up"), chapters.map { it.title })
        assertEquals(272_000L, chapters[1].startMs)
    }

    @Test
    fun `reads a list with the timestamp at the end of the line`() {
        val text = """
            Cold open — 0:00
            The interview — 4:32
            Wrap up — 58:10
        """.trimIndent()

        val chapters = chaptersFromDescription(text, durationMs = null)

        assertEquals(listOf("Cold open", "The interview", "Wrap up"), chapters.map { it.title })
    }

    @Test
    fun `refuses prose that happens to mention two times`() {
        val text = """
            We recorded this at 9:30 in the morning.
            The good bit is around 41:20 if you are in a hurry.
        """.trimIndent()

        assertTrue(chaptersFromDescription(text, durationMs = null).isEmpty())
    }

    @Test
    fun `refuses a list that does not start near the beginning`() {
        val text = """
            12:00 Something
            24:00 Something else
            36:00 A third thing
        """.trimIndent()

        assertTrue(chaptersFromDescription(text, durationMs = null).isEmpty())
    }

    @Test
    fun `refuses timestamps that do not run forwards`() {
        val text = """
            0:00 Intro
            18:00 Later
            4:00 Earlier again
        """.trimIndent()

        assertTrue(chaptersFromDescription(text, durationMs = null).isEmpty())
    }

    @Test
    fun `drops entries past the end of the episode`() {
        val text = """
            0:00 Intro
            4:00 Middle
            9:00 End
            90:00 A timestamp for a different episode
        """.trimIndent()

        val chapters = chaptersFromDescription(text, durationMs = 600_000L)

        assertEquals(listOf("Intro", "Middle", "End"), chapters.map { it.title })
    }

    @Test
    fun `refuses a list left too short by the duration check`() {
        val text = """
            0:00 Intro
            40:00 Middle
            80:00 End
        """.trimIndent()

        assertTrue(chaptersFromDescription(text, durationMs = 600_000L).isEmpty())
    }

    @Test
    fun `refuses a paragraph that merely begins with a timestamp`() {
        val long = "x".repeat(200)
        val text = """
            0:00 $long
            4:00 $long
            9:00 $long
        """.trimIndent()

        assertTrue(chaptersFromDescription(text, durationMs = null).isEmpty())
    }

    @Test
    fun `refuses an impossible clock reading`() {
        val text = """
            0:00 Intro
            99:99 Not a time
            9:00 End
        """.trimIndent()

        // Only two lines survive, which is below the minimum for a list.
        assertTrue(chaptersFromDescription(text, durationMs = null).isEmpty())
    }

    @Test
    fun `refuses two entries as too few to be a list`() {
        val text = """
            0:00 Intro
            9:00 End
        """.trimIndent()

        assertTrue(chaptersFromDescription(text, durationMs = null).isEmpty())
    }

    @Test
    fun `ignores everything past the line cap`() {
        val padding = List(600) { "nothing on this line" }.joinToString("\n")
        val text = padding + "\n0:00 Intro\n4:00 Middle\n9:00 End"

        assertTrue(chaptersFromDescription(text, durationMs = null).isEmpty())
    }

    @Test
    fun `an empty description yields nothing`() {
        assertTrue(chaptersFromDescription("", durationMs = null).isEmpty())
        assertTrue(chaptersFromDescription("   \n  ", durationMs = null).isEmpty())
    }
}

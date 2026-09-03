package md.borisveriga.megapodcastplayer.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the colour that replaced the watch's cover art.
 *
 * Two properties matter to the wearer. The same show must look the same every time, or the colour
 * teaches them nothing; and the palette must actually get used, or every show ends up the same
 * colour and the header is decoration rather than information.
 */
class ShowAccentTest {

    @Test
    fun `a show keeps its colour`() {
        assertEquals(showAccent("Radio Hardware"), showAccent("Radio Hardware"))
    }

    @Test
    fun `different shows usually get different colours`() {
        val shows = listOf(
            "Radio Hardware",
            "Signal Path",
            "The Rest Is History",
            "Darknet Diaries",
            "Lateral",
            "99% Invisible",
            "Search Engine",
            "Hard Fork",
        )

        // Eight names over eight colours will not be a perfect permutation, but a mapping that
        // collapsed them onto one or two colours would be no better than no colour at all.
        val distinct = shows.map(::showAccent).distinct().size
        assertTrue("only $distinct distinct colours for ${shows.size} shows", distinct >= 5)
    }

    /**
     * Long titles overflow the hash into negative numbers, which an index computed with `%` would
     * carry straight into an out-of-bounds read. This is that regression, spelled out.
     */
    @Test
    fun `a long title still lands on a colour`() {
        val long = "The Very Long Running Podcast About Absolutely Everything, Episode Guide".repeat(4)

        // Reaching the assertion at all is most of the test: an index out of range would throw here.
        assertNotEquals(0f, showAccent(long).alpha)
    }

    @Test
    fun `a show with no name still gets a colour`() {
        assertEquals(showAccent(""), showAccent(""))
        assertNotEquals(0f, showAccent("").alpha)
    }
}

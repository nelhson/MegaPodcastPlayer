package md.borisveriga.megapodcastplayer.core.common.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [toPlainText], driven by what real feeds actually publish.
 */
class PlainTextTest {

    @Test
    fun `strips html tags`() {
        assertEquals("Обсуждаем KMP", "<p>Обсуждаем <b>KMP</b></p>".toPlainText())
    }

    @Test
    fun `decodes named entities`() {
        assertEquals("Rambler&Co «тема»", "Rambler&amp;Co &laquo;тема&raquo;".toPlainText())
    }

    @Test
    fun `decodes numeric entities in decimal and hex`() {
        assertEquals("A\nB", "A&#10;B".toPlainText())
        assertEquals("AB", "&#x41;&#x42;".toPlainText())
    }

    @Test
    fun `decodes the double-escaped entities soundcloud publishes`() {
        // Exactly what Podlodka's feed contains: one SAX decode leaves "&#10;" and "&amp;" visible.
        val raw = "Rambler&amp;amp;Co&amp;#10;&amp;#10;- Егор Толстой"

        assertEquals("Rambler&Co\n\n- Егор Толстой", raw.toPlainText())
    }

    @Test
    fun `stops after two passes so a literal ampersand entity survives`() {
        // The author genuinely wanted to display the text "&amp;".
        assertEquals("&amp;", "&amp;amp;amp;".toPlainText())
    }

    @Test
    fun `turns block-level tags into line breaks`() {
        assertEquals("one\ntwo", "one<br/>two".toPlainText())
        assertEquals("one\ntwo", "<p>one</p><p>two</p>".toPlainText())
    }

    @Test
    fun `collapses runs of spaces and blank lines`() {
        assertEquals("a b", "a     b".toPlainText())
        assertEquals("a\n\nb", "a\n\n\n\n\nb".toPlainText())
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("text", "  \n text \n  ".toPlainText())
    }

    @Test
    fun `leaves plain text and empty input untouched`() {
        assertEquals("just text", "just text".toPlainText())
        assertEquals("", "".toPlainText())
    }

    @Test
    fun `leaves an unknown entity as written`() {
        assertEquals("&unknownentity;", "&unknownentity;".toPlainText())
    }

    @Test
    fun `strips a script tag rather than revealing its contents as markup`() {
        assertEquals("alert(1)", "&lt;script&gt;alert(1)&lt;/script&gt;".toPlainText())
    }
}

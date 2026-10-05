package md.borisveriga.megapodcastplayer.core.model.format

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for [formatVideoQuality]. */
class VideoQualityLabelTest {

    @Test
    fun `a video rendition is named by its height`() {
        assertEquals("720p", formatVideoQuality(720))
        assertEquals("1080p", formatVideoQuality(1080))
        assertEquals("144p", formatVideoQuality(144))
    }

    @Test
    fun `a rendition's height is written in plain digits whatever the locale`() {
        val previous = Locale.getDefault()
        try {
            // Arabic would otherwise be free to write its own digits; the label sits beside the
            // speed's, which never does.
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("1080p", formatVideoQuality(1080))
        } finally {
            Locale.setDefault(previous)
        }
    }
}

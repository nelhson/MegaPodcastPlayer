package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Tests for [VideoQuality]. */
class VideoQualityTest {

    @Test
    fun `the default is the preferred minimum`() {
        // The two numbers are documented as the same threshold seen from two sides; if they ever
        // drift apart the picker's default would sit below what the resolver reaches for first.
        assertEquals(VideoQuality.PREFERRED_MIN_HEIGHT, VideoQuality.DEFAULT.height)
    }

    @Test
    fun `a height must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { VideoQuality(0) }
        assertThrows(IllegalArgumentException::class.java) { VideoQuality(-1) }
    }

    @Test
    fun `equal heights are the same quality`() {
        assertEquals(VideoQuality(720), VideoQuality(720))
    }
}

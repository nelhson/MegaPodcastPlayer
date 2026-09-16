package md.borisveriga.megapodcastplayer.feature.podcast

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Tests for [downloadListFileName]. */
class DownloadListFileNameTest {

    @Test
    fun `the name carries the show and the day`() {
        assertEquals(
            "Podlodka Podcast downloads 2026-09-16.md",
            downloadListFileName("Podlodka Podcast", LocalDate.of(2026, 9, 16)),
        )
    }

    @Test
    fun `characters a file system refuses are removed from the show title`() {
        val name = downloadListFileName("AC/DC: Live?", LocalDate.of(2026, 9, 16))

        assertFalse(name, name.any { it in "/:?" })
    }
}

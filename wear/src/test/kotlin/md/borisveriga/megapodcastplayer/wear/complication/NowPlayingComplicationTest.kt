package md.borisveriga.megapodcastplayer.wear.complication

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks what the watch face is told.
 *
 * A complication gets a corner of somebody else's design, so every slot has to stand on its own: the
 * four-character one must not be blank, the spoken one must name the episode even when the visible
 * one is a dash, and none of them may claim progress through an episode whose length the phone has
 * not read.
 */
class NowPlayingComplicationTest {

    private val strings = ComplicationStrings(
        nothingPlaying = "Nothing playing",
        empty = "—",
        describeFormat = "%1\$s, %2\$s",
    )

    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "The one about batteries",
        showTitle = "Radio Hardware",
        isPlaying = true,
        positionMs = 600_000L,
        durationMs = 3_600_000L,
    )

    @Test
    fun `the short slot says how much is left, not how much has gone`() {
        val copy = complicationCopy(playing, positionMs = 600_000L, strings = strings)

        // Fifty minutes of a one-hour episode.
        assertEquals("50m", copy.shortText)
    }

    @Test
    fun `the spoken description names the episode and the show`() {
        val copy = complicationCopy(playing, positionMs = 600_000L, strings = strings)

        assertEquals("The one about batteries, Radio Hardware", copy.description)
    }

    @Test
    fun `a nameless show is described by its episode alone`() {
        val copy = complicationCopy(
            playing.copy(showTitle = ""),
            positionMs = 0L,
            strings = strings,
        )

        assertEquals("The one about batteries", copy.description)
        assertNull(copy.title)
    }

    @Test
    fun `the ring follows the position the caller extrapolated`() {
        val copy = complicationCopy(playing, positionMs = 1_800_000L, strings = strings)

        assertEquals(0.5f, copy.progress, TOLERANCE)
    }

    /**
     * A ring at zero is honest here: the phone has not read the length, so there is no fraction to
     * draw. Guessing one would put a moving ring on a face for an episode of unknown length.
     */
    @Test
    fun `an episode of unknown length claims no progress and no remaining time`() {
        val copy = complicationCopy(
            playing.copy(durationMs = 0L),
            positionMs = 600_000L,
            strings = strings,
        )

        assertEquals(0f, copy.progress, TOLERANCE)
        assertEquals("—", copy.shortText)
    }

    @Test
    fun `an idle phone still fills every slot`() {
        val copy = complicationCopy(NowPlayingSnapshot(), positionMs = 0L, strings = strings)

        assertEquals("—", copy.shortText)
        assertEquals("Nothing playing", copy.longText)
        assertEquals("Nothing playing", copy.description)
        assertNull(copy.title)
    }

    /** A watch that has never heard from the phone is in the same position as an idle one. */
    @Test
    fun `a phone that has never been heard from reads as nothing playing`() {
        val copy = complicationCopy(snapshot = null, positionMs = 0L, strings = strings)

        assertEquals("Nothing playing", copy.longText)
        assertTrue(copy.shortText.isNotBlank())
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}

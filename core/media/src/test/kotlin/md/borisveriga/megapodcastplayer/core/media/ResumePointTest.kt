package md.borisveriga.megapodcastplayer.core.media

import java.time.Instant
import md.borisveriga.megapodcastplayer.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [ResumePoint], the answer to "where was playback left".
 *
 * What matters is that the place survives the queue being filtered: the service drops episodes it
 * may not play, and a dropped entry ahead of the current one must not move the user onto its
 * neighbour.
 */
class ResumePointTest {

    private fun playable(id: String, positionMs: Long = 0L) = PlayableEpisode(
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
            positionMs = positionMs,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    private val point = ResumePoint(
        queue = listOf(playable("a", positionMs = 9_000L), playable("b"), playable("c")),
        index = 2,
        positionMs = 42_000L,
    )

    @Test
    fun `the episode is the entry at the index`() {
        assertEquals("c", point.episode?.episode?.id)
    }

    @Test
    fun `an empty point has no episode`() {
        assertNull(ResumePoint.EMPTY.episode)
    }

    @Test
    fun `keeping everything changes nothing`() {
        assertEquals(point, point.keeping { true })
    }

    @Test
    fun `an entry dropped ahead of the current one moves the index, not the episode`() {
        val kept = point.keeping { it.episode.id != "a" }

        assertEquals(listOf("b", "c"), kept.queue.map { it.episode.id })
        assertEquals(1, kept.index)
        assertEquals("c", kept.episode?.episode?.id)
        assertEquals(42_000L, kept.positionMs)
    }

    @Test
    fun `dropping the current entry falls back to the head at its own position`() {
        val kept = point.keeping { it.episode.id != "c" }

        assertEquals(0, kept.index)
        assertEquals("a", kept.episode?.episode?.id)
        // Not the dropped episode's position: 42 seconds into "c" means nothing in "a".
        assertEquals(9_000L, kept.positionMs)
    }

    @Test
    fun `dropping everything leaves nothing to resume`() {
        assertEquals(ResumePoint.EMPTY, point.keeping { false })
    }
}

package md.borisveriga.megapodcastplayer.feature.podcast

import java.time.Instant
import md.borisveriga.megapodcastplayer.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the show header's one button.
 *
 * The rule it encodes is an order of preference, and every step of it was a bug before: continue
 * what was started, then start the newest thing unheard, and only then offer a replay — saying so.
 * Pure, so it is tested without a screen.
 */
class HeaderActionTest {

    private fun episode(
        id: String,
        positionMs: Long = 0L,
        isPlayed: Boolean = false,
        durationMs: Long? = 3_600_000L,
    ) = Episode(
        id = id,
        podcastId = "podcast-1",
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = durationMs,
        publishedAt = Instant.parse("2026-09-01T06:00:00Z"),
        sizeBytes = null,
        positionMs = positionMs,
        isPlayed = isPlayed,
    )

    @Test
    fun `a show with no episodes has no button`() {
        assertNull(emptyList<Episode>().headerAction())
    }

    @Test
    fun `a fresh show plays its newest episode`() {
        val action = listOf(episode("newest"), episode("older")).headerAction()

        assertEquals(HeaderAction.Play("newest", isReplay = false), action)
    }

    @Test
    fun `an episode in progress wins over the newest one`() {
        val action = listOf(
            episode("newest"),
            episode("started", positionMs = 900_000L),
        ).headerAction()

        assertEquals(HeaderAction.Continue("started", remainingMs = 2_700_000L), action)
    }

    @Test
    fun `the newest thing in progress wins over an older one`() {
        // Feed order is newest first, so "the first that matches" is "the newest that matches".
        val action = listOf(
            episode("recent", positionMs = 60_000L),
            episode("ancient", positionMs = 3_000_000L),
        ).headerAction()

        assertEquals("recent", action?.episodeId)
    }

    @Test
    fun `a finished episode is not in progress, however far into it the position sits`() {
        // A played episode keeps its position, which is exactly why `positionMs > 0` was the wrong
        // question: on a fully caught-up show it is true of everything.
        val action = listOf(
            episode("done", positionMs = 3_500_000L, isPlayed = true),
            episode("fresh"),
        ).headerAction()

        assertEquals(HeaderAction.Play("fresh", isReplay = false), action)
    }

    @Test
    fun `a caught-up show offers the newest episode again, and says so`() {
        val action = listOf(
            episode("newest", isPlayed = true),
            episode("older", isPlayed = true),
        ).headerAction()

        assertEquals(HeaderAction.Play("newest", isReplay = true), action)
    }

    @Test
    fun `an unknown duration leaves the remaining time out rather than guessing`() {
        val action = listOf(episode("started", positionMs = 60_000L, durationMs = null))
            .headerAction()

        assertTrue(action is HeaderAction.Continue)
        assertNull((action as HeaderAction.Continue).remainingMs)
    }
}

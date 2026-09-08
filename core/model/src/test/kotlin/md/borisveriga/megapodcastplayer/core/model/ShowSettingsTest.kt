package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ShowSettings], [orderedBy] and [ShowSettingsCodec].
 *
 * Two things here would fail quietly rather than loudly, so they are pinned. A blob this build
 * cannot read has to become an empty map — every show back to its defaults — rather than an
 * exception thrown out of a preferences flow that nothing on the show page is prepared to catch.
 * And [orderedBy] has to reverse the list it is given rather than re-sort it by date, because a
 * feed with a missing `pubDate` would otherwise have those episodes flung to one end.
 */
class ShowSettingsTest {

    @Test
    fun `a show that has changed nothing is the default and is not worth storing`() {
        assertTrue(ShowSettings().isDefault)
        assertFalse(ShowSettings(episodeSort = EpisodeSort.OLDEST_FIRST).isDefault)
        assertFalse(ShowSettings(episodeFilter = EpisodeFilter.DOWNLOADED).isDefault)
    }

    @Test
    fun `newest first leaves the list exactly as it arrived`() {
        val episodes = listOf(episode("a"), episode("b"), episode("c"))

        assertEquals(episodes, episodes.orderedBy(EpisodeSort.NEWEST_FIRST))
    }

    @Test
    fun `oldest first reverses rather than re-sorting by date`() {
        // The middle one has no publication date at all, which is what a comparator would trip on.
        val episodes = listOf(
            episode("a", publishedAt = Instant.ofEpochMilli(3_000L)),
            episode("undated", publishedAt = null),
            episode("c", publishedAt = Instant.ofEpochMilli(1_000L)),
        )

        val reversed = episodes.orderedBy(EpisodeSort.OLDEST_FIRST)

        assertEquals(listOf("c", "undated", "a"), reversed.map { it.id })
    }

    @Test
    fun `settings survive a round trip`() {
        val settings = mapOf(
            "show-1" to ShowSettings(
                episodeSort = EpisodeSort.OLDEST_FIRST,
                episodeFilter = EpisodeFilter.DOWNLOADED,
            ),
            "show-2" to ShowSettings(episodeFilter = EpisodeFilter.IN_PROGRESS),
        )

        assertEquals(settings, ShowSettingsCodec.decode(ShowSettingsCodec.encode(settings)))
    }

    @Test
    fun `nothing stored decodes to nothing`() {
        assertEquals(emptyMap<String, ShowSettings>(), ShowSettingsCodec.decode(null))
        assertEquals(emptyMap<String, ShowSettings>(), ShowSettingsCodec.decode(""))
    }

    @Test
    fun `an unreadable blob decodes to nothing rather than throwing`() {
        // The failure this protects against is not a hand-edited file: it is this app's own
        // stored value after a value has been renamed, read by the build that renamed it.
        assertEquals(emptyMap<String, ShowSettings>(), ShowSettingsCodec.decode("not json"))
        assertEquals(
            emptyMap<String, ShowSettings>(),
            ShowSettingsCodec.decode("""{"show-1":{"episodeSort":"SIDEWAYS"}}"""),
        )
    }

    /**
     * A minimal episode.
     *
     * @param id its id, which is all these tests assert on.
     * @param publishedAt its publication date, null for a feed that omits one.
     * @return the episode.
     */
    private fun episode(id: String, publishedAt: Instant? = null) = Episode(
        id = id,
        podcastId = "show-1",
        guid = id,
        title = id,
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 1_000L,
        publishedAt = publishedAt,
        sizeBytes = null,
    )
}

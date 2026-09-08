package md.borisveriga.megapodcastplayer.core.model.backup

import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BackupCodec], covering the round trip and every way a picked file can disappoint.
 */
class BackupCodecTest {

    private val populated = BackupFile(
        exportedAtMs = 1_789_084_800_000L,
        podcasts = listOf(
            BackupPodcast(
                feedUrl = "https://feeds.simplecast.com/podlodka",
                source = PodcastSource.RSS,
                title = "Podlodka Podcast",
                author = "Podlodka",
                itunesId = 1_209_828_744L,
                sortOrder = 0,
                autoRefresh = true,
                addedAtMs = 1_750_000_000_000L,
            ),
        ),
        episodes = listOf(
            BackupEpisodeState("https://feeds.simplecast.com/podlodka", "guid-1", 743_000L, false),
        ),
        queue = listOf(BackupQueueEntry("https://feeds.simplecast.com/podlodka", "guid-1", 0)),
        downloads = listOf(BackupDownload("https://feeds.simplecast.com/podlodka", "guid-1")),
        moments = listOf(
            BackupMoment("https://feeds.simplecast.com/podlodka", "guid-1", 743_000L, "the KMP bit", 1L),
        ),
    )

    @Test
    fun `round-trips a fully populated document`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(populated))

        assertEquals(BackupDecodeResult.Decoded(populated), decoded)
    }

    @Test
    fun `writes the version into the document`() {
        val text = BackupCodec.encode(populated)

        assertTrue(text, text.contains("\"version\": 1"))
    }

    @Test
    fun `writes empty lists rather than omitting them`() {
        val text = BackupCodec.encode(BackupFile(exportedAtMs = 0L))

        assertTrue(text, text.contains("\"moments\""))
    }

    @Test
    fun `decodes a document carrying a key this build does not know`() {
        val text = """
            {
              "version": 1,
              "exportedAtMs": 5,
              "somethingFromTheFuture": { "nested": true },
              "podcasts": []
            }
        """.trimIndent()

        val decoded = BackupCodec.decode(text)

        assertEquals(BackupDecodeResult.Decoded(BackupFile(exportedAtMs = 5L)), decoded)
    }

    @Test
    fun `fills a missing moments list from its default`() {
        val text = """{ "version": 1, "exportedAtMs": 5 }"""

        val decoded = BackupCodec.decode(text) as BackupDecodeResult.Decoded

        assertEquals(emptyList<BackupMoment>(), decoded.file.moments)
    }

    @Test
    fun `refuses a document from a newer writer`() {
        val text = """{ "version": 99, "exportedAtMs": 5 }"""

        assertEquals(BackupDecodeResult.TooNew(99), BackupCodec.decode(text))
    }

    @Test
    fun `reports truncated json as malformed rather than throwing`() {
        val truncated = BackupCodec.encode(populated).take(80)

        assertTrue(BackupCodec.decode(truncated) is BackupDecodeResult.Malformed)
    }

    @Test
    fun `reports an unrelated json document as malformed`() {
        assertTrue(BackupCodec.decode("""{ "shoppingList": ["milk"] }""") is BackupDecodeResult.Malformed)
    }

    @Test
    fun `reports text that is not json at all as malformed`() {
        assertTrue(BackupCodec.decode("not a backup") is BackupDecodeResult.Malformed)
    }
}

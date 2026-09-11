package md.borisveriga.megapodcastplayer.core.model.backup

import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BackupCodec], covering the round trip and every way a handover can disappoint.
 */
class BackupCodecTest {

    private val populated = BackupFile(
        exportedAtMs = 1_789_084_800_000L,
        podcasts = listOf(
            BackupPodcast(
                feedUrl = "https://feeds.simplecast.com/podlodka",
                source = PodcastSource.RSS,
                title = "Podlodka Podcast",
                sortOrder = 0,
            ),
            BackupPodcast(
                feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=PL1",
                source = PodcastSource.YOUTUBE,
                title = "A playlist",
                sortOrder = 1,
            ),
        ),
    )

    @Test
    fun `round-trips a populated document`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(populated))

        assertEquals(BackupDecodeResult.Decoded(populated), decoded)
    }

    @Test
    fun `writes an empty list rather than omitting it`() {
        val text = BackupCodec.encode(BackupFile(exportedAtMs = 0L))

        assertTrue(text, text.contains("\"podcasts\""))
    }

    @Test
    fun `refuses a document carrying a key this build does not know`() {
        // Both ends of a handover ship from one build, so an unknown key is a stale or corrupted
        // file rather than a newer peer — and half a library is worse than none.
        val text = """
            {
              "exportedAtMs": 5,
              "somethingFromAnInstallLongGone": { "nested": true },
              "podcasts": []
            }
        """.trimIndent()

        assertTrue(BackupCodec.decode(text) is BackupDecodeResult.Malformed)
    }

    @Test
    fun `fills a missing podcast list from its default`() {
        val decoded = BackupCodec.decode("""{ "exportedAtMs": 5 }""") as BackupDecodeResult.Decoded

        assertEquals(emptyList<BackupPodcast>(), decoded.file.podcasts)
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
        assertTrue(BackupCodec.decode("not a handover") is BackupDecodeResult.Malformed)
    }
}

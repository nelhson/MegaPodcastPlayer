package md.borisveriga.megapodcastplayer.core.data.chapters

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import md.borisveriga.megapodcastplayer.core.model.chapters.ChapterJson
import md.borisveriga.megapodcastplayer.core.model.chapters.ChapterSource
import md.borisveriga.megapodcastplayer.core.network.chapters.ChapterEntry
import md.borisveriga.megapodcastplayer.core.network.chapters.ChaptersApi
import md.borisveriga.megapodcastplayer.core.network.chapters.ChaptersDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ChapterResolver].
 *
 * The whole class is an order of preference plus a cache, and both are the kind of thing that is
 * invisible when wrong: a resolver that always went to the network would put a round trip in front
 * of every sheet, and one that never did would leave every publisher who hosts their chapters
 * properly looking as though they publish none.
 */
class ChapterResolverTest {

    private lateinit var chaptersApi: ChaptersApi
    private lateinit var episodeDao: EpisodeDao
    private lateinit var crashReporter: CrashReporter
    private lateinit var resolver: ChapterResolver

    private fun episode(
        description: String = "",
        chaptersUrl: String? = null,
        chaptersJson: String? = null,
        durationMs: Long? = 3_600_000L,
    ) = Episode(
        id = "e1",
        podcastId = "p1",
        guid = "guid",
        title = "Episode",
        description = description,
        audioUrl = "https://cdn.example.com/e1.mp3",
        artworkUrl = null,
        durationMs = durationMs,
        publishedAt = Instant.parse("2026-09-01T06:00:00Z"),
        sizeBytes = null,
        chaptersUrl = chaptersUrl,
        chaptersJson = chaptersJson,
    )

    @Before
    fun setUp() {
        chaptersApi = mockk(relaxed = true)
        episodeDao = mockk(relaxed = true)
        crashReporter = mockk(relaxed = true)
        resolver = ChapterResolver(chaptersApi, episodeDao, crashReporter)
    }

    @Test
    fun `an episode with nothing to read has no chapters`() = runTest {
        val resolved = resolver.chaptersFor(episode(description = "A conversation about things."))

        assertEquals(EpisodeChapters(), resolved)
        coVerify(exactly = 0) { chaptersApi.getChapters(any()) }
    }

    @Test
    fun `a stored list is used without asking the network`() = runTest {
        val stored = listOf(Chapter(startMs = 0L, title = "Intro"))

        val resolved = resolver.chaptersFor(
            episode(chaptersJson = ChapterJson.encode(stored), chaptersUrl = "https://x/c.json"),
        )

        assertEquals(stored, resolved.chapters)
        assertEquals(ChapterSource.INLINE_PSC, resolved.source)
        // The point of the cache: a document that has already been read is never fetched again.
        coVerify(exactly = 0) { chaptersApi.getChapters(any()) }
    }

    @Test
    fun `the publisher's document is fetched and kept`() = runTest {
        coEvery { chaptersApi.getChapters("https://x/c.json") } returns ChaptersDocument(
            chapters = listOf(
                ChapterEntry(startTime = 0.0, title = "Intro"),
                ChapterEntry(startTime = 90.5, title = "The interview", image = "https://x/i.png"),
            ),
        )

        val resolved = resolver.chaptersFor(episode(chaptersUrl = "https://x/c.json"))

        assertEquals(ChapterSource.REMOTE_JSON, resolved.source)
        assertEquals(listOf(0L, 90_500L), resolved.chapters.map { it.startMs })
        assertEquals("https://x/i.png", resolved.chapters[1].imageUrl)
        // Written back, so the next reader gets it from disk.
        coVerify { episodeDao.setChaptersJson("e1", any()) }
    }

    @Test
    fun `a fetch that fails leaves the episode without chapters rather than failing`() = runTest {
        // A document that arrived and could not be read: the publisher's problem, worth knowing.
        coEvery { chaptersApi.getChapters(any()) } throws
            IllegalArgumentException("Unexpected JSON token")

        val resolved = resolver.chaptersFor(episode(chaptersUrl = "https://x/c.json"))

        assertEquals(EpisodeChapters(), resolved)
        // Invisible to the user, and it should be — but it still goes somewhere.
        coVerify { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `a fetch that fails for want of a network is not reported`() = runTest {
        coEvery { chaptersApi.getChapters(any()) } throws UnknownHostException("x")

        val resolved = resolver.chaptersFor(episode(chaptersUrl = "https://x/c.json"))

        assertEquals(EpisodeChapters(), resolved)
        // Nothing is wrong with the document; the next open simply tries again.
        coVerify(exactly = 0) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `a failed fetch falls through to the description`() = runTest {
        coEvery { chaptersApi.getChapters(any()) } throws IOException("offline")

        val resolved = resolver.chaptersFor(
            episode(
                chaptersUrl = "https://x/c.json",
                description = """
                    00:00 Intro
                    03:20 The interview
                    41:05 Outro
                """.trimIndent(),
            ),
        )

        assertEquals(ChapterSource.DESCRIPTION, resolved.source)
        assertEquals(listOf("Intro", "The interview", "Outro"), resolved.chapters.map { it.title })
    }

    @Test
    fun `an empty document is not cached, so the description still gets a turn`() = runTest {
        coEvery { chaptersApi.getChapters(any()) } returns ChaptersDocument()

        val resolved = resolver.chaptersFor(
            episode(
                chaptersUrl = "https://x/c.json",
                description = "00:00 Intro\n12:30 Part two\n45:00 Part three",
            ),
        )

        assertEquals(ChapterSource.DESCRIPTION, resolved.source)
        // Caching "[]" would read back as "the feed says there are none" and stop this ever
        // happening again.
        coVerify(exactly = 0) { episodeDao.setChaptersJson(any(), any()) }
    }

    @Test
    fun `chapters read out of a description are marked as inferred`() = runTest {
        val resolved = resolver.chaptersFor(
            episode(description = "0:00 Cold open\n5:00 Chapter two\n30:00 Chapter three"),
        )

        assertEquals(ChapterSource.DESCRIPTION, resolved.source)
        assertTrue(resolved.isNotEmpty)
    }
}

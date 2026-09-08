package md.borisveriga.megapodcastplayer.core.data.chapters

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import md.borisveriga.megapodcastplayer.core.model.chapters.ChapterJson
import md.borisveriga.megapodcastplayer.core.network.chapters.ChaptersApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [EpisodeChapterSource].
 *
 * Small enough that the only thing worth asserting is the thing it exists for: the playback service
 * gets [ChapterResolver]'s answer, the same one the player screen gets, and a queue entry whose
 * episode row has gone gets an absence rather than an exception out of a media session callback.
 */
class EpisodeChapterSourceTest {

    private val chapters = listOf(
        Chapter(startMs = 0L, title = "Intro"),
        Chapter(startMs = 90_000L, title = "The interview"),
    )

    private val episodeDao: EpisodeDao = mockk()
    private val chaptersApi: ChaptersApi = mockk(relaxed = true)
    private val crashReporter: CrashReporter = mockk(relaxed = true)

    private val source = EpisodeChapterSource(
        episodeDao = episodeDao,
        chapterResolver = ChapterResolver(
            chaptersApi = chaptersApi,
            episodeDao = episodeDao,
            crashReporter = crashReporter,
        ),
    )

    @Test
    fun `an episode's stored chapters are what the service is given`() = runTest {
        coEvery { episodeDao.getById("e1") } returns
            episodeEntity(chaptersJson = ChapterJson.encode(chapters))

        assertEquals(chapters, source.chaptersFor("e1"))
    }

    @Test
    fun `an episode with nothing to go on has no chapters`() = runTest {
        coEvery { episodeDao.getById("e1") } returns episodeEntity()

        assertEquals(emptyList<Chapter>(), source.chaptersFor("e1"))
    }

    @Test
    fun `an episode the queue outlived has no chapters rather than a failure`() = runTest {
        coEvery { episodeDao.getById("gone") } returns null

        assertEquals(emptyList<Chapter>(), source.chaptersFor("gone"))
    }

    private fun episodeEntity(chaptersJson: String? = null) = EpisodeEntity(
        id = "e1",
        podcastId = "p1",
        guid = "guid-e1",
        title = "Episode",
        description = "notes with no timestamps in them",
        audioUrl = "https://cdn.example.com/e1.mp3",
        artworkUrl = null,
        durationMs = 3_600_000L,
        publishedAt = 1_000L,
        sizeBytes = null,
        chaptersJson = chaptersJson,
    )
}

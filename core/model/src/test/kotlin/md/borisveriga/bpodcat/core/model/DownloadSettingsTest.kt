package md.borisveriga.bpodcat.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [DownloadSettings]'s keep-limit sweep. */
class DownloadSettingsTest {

    @Test
    fun `keep-all sweeps nothing`() {
        val settings = DownloadSettings(keepLimitPerPodcast = DownloadSettings.KEEP_ALL)

        assertFalse(settings.enforcesKeepLimit)
        assertTrue(settings.episodesToSweep(episodes(5)).isEmpty())
    }

    @Test
    fun `a show under the limit sweeps nothing`() {
        val settings = DownloadSettings(keepLimitPerPodcast = 3)

        assertTrue(settings.episodesToSweep(episodes(3)).isEmpty())
    }

    @Test
    fun `the oldest episodes over the limit are swept, oldest first`() {
        val settings = DownloadSettings(keepLimitPerPodcast = 2)

        val swept = settings.episodesToSweep(episodes(5)).map { it.id }

        // episodes() is newest-first, so e5 is the oldest of the five.
        assertEquals(listOf("e5", "e4", "e3"), swept)
    }

    @Test
    fun `protected episodes are never swept`() {
        val settings = DownloadSettings(keepLimitPerPodcast = 2)

        val swept = settings.episodesToSweep(
            downloadedNewestFirst = episodes(5),
            protectedIds = setOf("e5", "e4"),
        ).map { it.id }

        // Three are over the limit, but the two oldest are queued, so only e3 can go.
        assertEquals(listOf("e3"), swept)
    }

    @Test
    fun `an entirely protected show sweeps nothing`() {
        val settings = DownloadSettings(keepLimitPerPodcast = 1)

        val swept = settings.episodesToSweep(
            downloadedNewestFirst = episodes(3),
            protectedIds = setOf("e1", "e2", "e3"),
        )

        assertTrue(swept.isEmpty())
    }

    /** [count] downloaded episodes, newest first, ids `e1`..`e{count}`. */
    private fun episodes(count: Int): List<Episode> = (1..count).map { index ->
        Episode(
            id = "e$index",
            podcastId = "p1",
            guid = "g$index",
            title = "Episode $index",
            description = "",
            audioUrl = "https://example.com/$index.mp3",
            artworkUrl = null,
            durationMs = null,
            publishedAt = Instant.EPOCH.plusSeconds((count - index).toLong()),
            sizeBytes = null,
            downloadState = DownloadState.COMPLETED,
        )
    }
}

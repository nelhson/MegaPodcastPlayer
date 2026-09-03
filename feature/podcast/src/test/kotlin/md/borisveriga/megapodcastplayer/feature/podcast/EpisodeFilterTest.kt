package md.borisveriga.megapodcastplayer.feature.podcast

import java.time.Instant
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the show page's filter chips.
 *
 * The rule worth pinning is what "Unplayed" means. An episode someone started and abandoned is not
 * new, and the obvious implementation — everything that is not finished — would put it there,
 * turning a list of things to start into a list of things already begun.
 */
class EpisodeFilterTest {

    private val untouched = episode(id = "untouched")
    private val started = episode(id = "started", positionMs = 600_000L)
    private val finished = episode(id = "finished", positionMs = 5_000_000L, isPlayed = true)
    private val downloaded = episode(id = "downloaded", downloadState = DownloadState.COMPLETED)
    private val downloading = episode(id = "downloading", downloadState = DownloadState.DOWNLOADING)

    private val all = listOf(untouched, started, finished, downloaded, downloading)

    @Test
    fun `all keeps every episode, in the order it arrived`() {
        assertEquals(all, all.filterBy(EpisodeFilter.ALL))
    }

    @Test
    fun `unplayed excludes both finished and half-finished episodes`() {
        assertEquals(
            listOf("untouched", "downloaded", "downloading"),
            all.filterBy(EpisodeFilter.UNPLAYED).map { it.id },
        )
    }

    @Test
    fun `in progress is exactly what was started and not finished`() {
        assertEquals(listOf("started"), all.filterBy(EpisodeFilter.IN_PROGRESS).map { it.id })
    }

    @Test
    fun `downloaded means the audio is on the device, not that a transfer was asked for`() {
        assertEquals(listOf("downloaded"), all.filterBy(EpisodeFilter.DOWNLOADED).map { it.id })
    }

    @Test
    fun `a filter that matches nothing returns an empty list rather than everything`() {
        assertEquals(emptyList<Episode>(), listOf(finished).filterBy(EpisodeFilter.IN_PROGRESS))
    }

    /**
     * One episode for the table above.
     *
     * @param id the episode id, which is what the assertions compare.
     * @param positionMs how far playback got.
     * @param isPlayed whether it was finished.
     * @param downloadState the offline state.
     */
    private fun episode(
        id: String,
        positionMs: Long = 0L,
        isPlayed: Boolean = false,
        downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    ) = Episode(
        id = id,
        podcastId = "p1",
        guid = id,
        title = id,
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 5_025_000L,
        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
        sizeBytes = null,
        positionMs = positionMs,
        isPlayed = isPlayed,
        downloadState = downloadState,
    )
}

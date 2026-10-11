package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [totals].
 *
 * One rule matters: only what has finished counts, sound and picture alike, because the figure is
 * what deleting the rows would give back.
 */
class DownloadTotalsTest {

    private fun download(id: String, state: DownloadState, bytes: Long) = EpisodeWithShow(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.EPOCH,
            sizeBytes = null,
            downloadState = state,
            downloadedBytes = bytes,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    @Test
    fun `only finished downloads and finished videos are counted`() {
        val rows = listOf(
            download("done", DownloadState.COMPLETED, 1_000L),
            download("busy", DownloadState.DOWNLOADING, 500L),
            download("other", DownloadState.COMPLETED, 2_000L),
        )
        val videos = mapOf(
            "done" to VideoDownload(VideoQuality(720), DownloadState.COMPLETED, 100f, 4_000L),
            "other" to VideoDownload(VideoQuality(720), DownloadState.DOWNLOADING, 50f, 3_000L),
            // A video of a row that is not in the set does not count towards it.
            "elsewhere" to VideoDownload(VideoQuality(720), DownloadState.COMPLETED, 100f, 9_000L),
        )

        assertEquals(DownloadTotals(completedCount = 2, totalBytes = 7_000L), rows.totals(videos))
    }

    @Test
    fun `nothing costs nothing`() {
        assertEquals(DownloadTotals(0, 0L), emptyList<EpisodeWithShow>().totals(emptyMap()))
    }
}

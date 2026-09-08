package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [groupIntoSections].
 *
 * Two properties matter to the screen above it. The order of the sections is fixed and puts the
 * problems first — a failure is the only row on that screen waiting on the user — and the order
 * *within* a section is untouched, because among the finished episodes that order is the
 * arrangement the user dragged into place.
 */
class DownloadSectionTest {

    private fun download(id: String, state: DownloadState) = EpisodeWithShow(
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
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    @Test
    fun `the sections come out problems first`() {
        val grouped = listOf(
            download("ready", DownloadState.COMPLETED),
            download("waiting", DownloadState.QUEUED),
            download("moving", DownloadState.DOWNLOADING),
            download("failed", DownloadState.FAILED),
        ).groupIntoSections()

        assertEquals(
            listOf(
                DownloadSection.FAILED,
                DownloadSection.DOWNLOADING,
                DownloadSection.WAITING,
                DownloadSection.READY,
            ),
            grouped.map { it.section },
        )
    }

    @Test
    fun `a section with nothing in it is not drawn at all`() {
        val grouped = listOf(
            download("a", DownloadState.COMPLETED),
            download("b", DownloadState.COMPLETED),
        ).groupIntoSections()

        assertEquals(listOf(DownloadSection.READY), grouped.map { it.section })
        assertEquals(2, grouped.single().downloads.size)
    }

    /** The order among finished episodes is the arrangement the user dragged into place. */
    @Test
    fun `the order within a section is the order it arrived in`() {
        val grouped = listOf(
            download("third", DownloadState.COMPLETED),
            download("first", DownloadState.COMPLETED),
            download("second", DownloadState.COMPLETED),
        ).groupIntoSections()

        assertEquals(
            listOf("third", "first", "second"),
            grouped.single().downloads.map { it.episode.id },
        )
    }

    @Test
    fun `only the ready section can be rearranged`() {
        val grouped = listOf(
            download("ready", DownloadState.COMPLETED),
            download("waiting", DownloadState.QUEUED),
        ).groupIntoSections()

        assertTrue(grouped.single { it.section == DownloadSection.READY }.isReorderable)
        assertFalse(grouped.single { it.section == DownloadSection.WAITING }.isReorderable)
    }

    @Test
    fun `an empty list has no sections rather than four empty ones`() {
        assertEquals(emptyList<DownloadGroup>(), emptyList<EpisodeWithShow>().groupIntoSections())
    }
}

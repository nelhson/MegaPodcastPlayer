package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for [DownloadKindFilter], [filteredBy] and [mergeVisibleOrder].
 *
 * The filter's one subtlety is what *Audio* means — sound only, so that Audio and Video split the
 * list between them. The merge's is that a drag on a narrowed list must not move, or drop, a row
 * that was not on screen.
 */
class DownloadKindFilterTest {

    private fun download(id: String) = EpisodeWithShow(
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
            downloadState = DownloadState.COMPLETED,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    private val downloads = listOf(download("sound"), download("picture"), download("failing"))

    private val videos = mapOf(
        "picture" to VideoDownload(VideoQuality(720), DownloadState.COMPLETED, 100f),
        // A picture that failed is still a video download, and still what the Video chip finds.
        "failing" to VideoDownload(VideoQuality(1080), DownloadState.FAILED, 0f),
    )

    private fun List<EpisodeWithShow>.ids() = map { it.episode.id }

    @Test
    fun `all is the list itself`() {
        assertSame(downloads, downloads.filteredBy(DownloadKindFilter.ALL, videos))
    }

    @Test
    fun `audio is the downloads with no video in any state`() {
        assertEquals(listOf("sound"), downloads.filteredBy(DownloadKindFilter.AUDIO, videos).ids())
    }

    @Test
    fun `video is the downloads with a video in any state, in their order`() {
        assertEquals(
            listOf("picture", "failing"),
            downloads.filteredBy(DownloadKindFilter.VIDEO, videos).ids(),
        )
    }

    @Test
    fun `audio and video between them are every download exactly once`() {
        val audio = downloads.filteredBy(DownloadKindFilter.AUDIO, videos).ids()
        val video = downloads.filteredBy(DownloadKindFilter.VIDEO, videos).ids()

        assertEquals(downloads.ids().sorted(), (audio + video).sorted())
    }

    @Test
    fun `with no videos at all the video filter is empty`() {
        assertEquals(emptyList<String>(), downloads.filteredBy(DownloadKindFilter.VIDEO, emptyMap()).ids())
    }

    @Test
    fun `a merge with nothing hidden is the dragged order`() {
        assertEquals(
            listOf("c", "a", "b"),
            mergeVisibleOrder(fullOrder = listOf("a", "b", "c"), visibleReordered = listOf("c", "a", "b")),
        )
    }

    @Test
    fun `hidden rows keep their slots while the visible ones swap around them`() {
        assertEquals(
            listOf("d", "x", "b", "y", "a"),
            mergeVisibleOrder(
                fullOrder = listOf("a", "x", "b", "y", "d"),
                visibleReordered = listOf("d", "b", "a"),
            ),
        )
    }

    @Test
    fun `a row that arrived during the drag goes after the rest`() {
        assertEquals(
            listOf("b", "x", "a", "new"),
            mergeVisibleOrder(fullOrder = listOf("a", "x", "b"), visibleReordered = listOf("b", "a", "new")),
        )
    }

    @Test
    fun `empty lists merge to empty`() {
        assertEquals(emptyList<String>(), mergeVisibleOrder(emptyList(), emptyList()))
    }
}

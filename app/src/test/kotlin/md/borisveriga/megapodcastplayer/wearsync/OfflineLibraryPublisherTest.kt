package md.borisveriga.megapodcastplayer.wearsync

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the one decision in publishing the offline library: what the watch is offered.
 *
 * The rest of that class is Play Services plumbing. This is the part with a bug in it — an entry the
 * watch can tap and get half an episode from, or a list long enough to overflow the data item it
 * travels in and take the whole thing with it.
 */
class OfflineLibraryPublisherTest {

    @Test
    fun `only finished downloads are offered`() {
        val library = offlineLibraryOf(
            listOf(
                downloaded("ep-1", DownloadState.COMPLETED),
                downloaded("ep-2", DownloadState.DOWNLOADING),
                downloaded("ep-3", DownloadState.QUEUED),
                downloaded("ep-4", DownloadState.FAILED),
                downloaded("ep-5", DownloadState.NOT_DOWNLOADED),
            ),
        )

        assertEquals(listOf("ep-1"), library.episodes.map { it.id })
    }

    @Test
    fun `an offered episode carries what the row has to show`() {
        val library = offlineLibraryOf(listOf(downloaded("ep-1", DownloadState.COMPLETED)))

        val episode = library.episodes.single()
        assertEquals("The one about batteries", episode.title)
        assertEquals("Radio Hardware", episode.showTitle)
        assertEquals(3_600_000L, episode.durationMs)
    }

    /**
     * The bytes on disk, not the enclosure length the feed advertised: this number is shown before a
     * transfer that takes minutes, and feeds are routinely wrong about it.
     */
    @Test
    fun `the size offered is what was actually written`() {
        val library = offlineLibraryOf(
            listOf(
                downloaded("ep-1", DownloadState.COMPLETED).let { entry ->
                    entry.copy(
                        episode = entry.episode.copy(
                            sizeBytes = 99_000_000L,
                            downloadedBytes = 28_000_000L,
                        ),
                    )
                },
            ),
        )

        assertEquals(28_000_000L, library.episodes.single().sizeBytes)
    }

    /**
     * A data item is capped at 100 KB. A library of two thousand downloads would overflow it, and an
     * overflowing item does not arrive truncated — it does not arrive.
     */
    @Test
    fun `a very large library is cut down to what a data item can carry`() {
        val many = (1..500).map { downloaded("ep-$it", DownloadState.COMPLETED) }

        val library = offlineLibraryOf(many)

        assertTrue(library.episodes.size < many.size)
        // Whatever the cap is, the list has to stay comfortably inside the item it travels in.
        assertTrue(WearMessages.encodeLibrary(library).size < DATA_ITEM_LIMIT_BYTES)
    }

    @Test
    fun `nothing downloaded is an empty offer rather than no answer`() {
        assertTrue(offlineLibraryOf(emptyList()).episodes.isEmpty())
    }

    /**
     * The write is urgent, so it has to be rare. A download in flight moves the rows the phone's own
     * screen draws many times a second and changes nothing here, and each of those must not become a
     * Bluetooth write.
     */
    @Test
    fun `a download in progress is not republished on every byte`() = runTest {
        val downloads = flowOf(
            listOf(downloaded("ep-1", DownloadState.COMPLETED)),
            listOf(
                downloaded("ep-1", DownloadState.COMPLETED),
                downloaded("ep-2", DownloadState.DOWNLOADING).progressed(1_000_000L),
            ),
            listOf(
                downloaded("ep-1", DownloadState.COMPLETED),
                downloaded("ep-2", DownloadState.DOWNLOADING).progressed(9_000_000L),
            ),
        )

        val published = offlineLibraries(downloads).toList()

        assertEquals(1, published.size)
        assertEquals(listOf("ep-1"), published.single().episodes.map { it.id })
    }

    /** A download that finishes is exactly the change the watch is waiting for. */
    @Test
    fun `a download finishing is published`() = runTest {
        val downloads = flowOf(
            listOf(downloaded("ep-1", DownloadState.DOWNLOADING)),
            listOf(downloaded("ep-1", DownloadState.COMPLETED)),
        )

        val published = offlineLibraries(downloads).toList()

        val ids = published.map { library -> library.episodes.map { it.id } }
        assertEquals(listOf(emptyList<String>(), listOf("ep-1")), ids)
    }

    /** How far a download in flight has got, which the offered list must not react to. */
    private fun EpisodeWithShow.progressed(bytes: Long) =
        copy(episode = episode.copy(downloadedBytes = bytes))

    /** An episode in some download state, with the fields the library actually reads. */
    private fun downloaded(id: String, state: DownloadState) = EpisodeWithShow(
        episode = Episode(
            id = id,
            podcastId = "show-1",
            guid = id,
            title = "The one about batteries",
            description = "",
            audioUrl = "https://example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 3_600_000L,
            publishedAt = null,
            sizeBytes = 28_000_000L,
            downloadState = state,
            downloadedBytes = 28_000_000L,
        ),
        showTitle = "Radio Hardware",
        showArtworkUrl = null,
    )

    private companion object {
        /** What the Data Layer will carry in one item; the cap exists to stay well inside it. */
        const val DATA_ITEM_LIMIT_BYTES = 100_000
    }
}

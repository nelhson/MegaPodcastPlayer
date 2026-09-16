package md.borisveriga.megapodcastplayer.core.data.export

import java.time.Instant
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [exportNetworkFor]: which network a *Download and export* run waits for.
 *
 * The rule that matters is the first: a run with nothing left to download must not wait for Wi-Fi,
 * or a plain copy of downloads already on the phone would sit queued on mobile data for no reason.
 */
class ExportNetworkTest {

    private fun episode(id: String, state: DownloadState, isPlayed: Boolean = false) = Episode(
        id = id,
        podcastId = "show",
        guid = id,
        title = "Episode $id",
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = null,
        durationMs = null,
        publishedAt = Instant.EPOCH,
        sizeBytes = null,
        isPlayed = isPlayed,
        downloadState = state,
    )

    @Test
    fun `nothing left to download needs no network, whatever the setting`() {
        val episodes = listOf(episode("a", DownloadState.COMPLETED))

        assertEquals(ExportNetwork.NONE, exportNetworkFor(episodes, EpisodeFilter.ALL, true))
        assertEquals(ExportNetwork.NONE, exportNetworkFor(episodes, EpisodeFilter.ALL, false))
    }

    @Test
    fun `something to download waits for the network the download setting requires`() {
        val episodes = listOf(episode("a", DownloadState.NOT_DOWNLOADED))

        assertEquals(ExportNetwork.UNMETERED, exportNetworkFor(episodes, EpisodeFilter.ALL, true))
        assertEquals(ExportNetwork.CONNECTED, exportNetworkFor(episodes, EpisodeFilter.ALL, false))
    }

    @Test
    fun `only the episodes the filter lists count`() {
        // The one still to download is played, so an Unplayed export has nothing to fetch.
        val episodes = listOf(
            episode("a", DownloadState.COMPLETED),
            episode("b", DownloadState.FAILED, isPlayed = true),
        )

        assertEquals(ExportNetwork.NONE, exportNetworkFor(episodes, EpisodeFilter.UNPLAYED, true))
        assertEquals(ExportNetwork.UNMETERED, exportNetworkFor(episodes, EpisodeFilter.ALL, true))
    }

    @Test
    fun `an empty selection needs no network`() {
        assertEquals(ExportNetwork.NONE, exportNetworkFor(emptyList(), EpisodeFilter.ALL, true))
    }
}

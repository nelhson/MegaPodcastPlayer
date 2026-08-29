package md.borisveriga.bpodcat.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.time.Instant
import md.borisveriga.bpodcat.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the [PlayableEpisode] to [MediaItem] mapping.
 *
 * Robolectric is needed only because `MediaMetadata` stores artwork as an `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemsTest {

    private fun episode(
        id: String = "episode-1",
        artworkUrl: String? = null,
        durationMs: Long? = 3_600_000L,
    ) = Episode(
        id = id,
        podcastId = "podcast-1",
        guid = "guid-1",
        title = "Podlodka #400",
        description = "notes",
        audioUrl = "https://cdn.example.com/400.mp3",
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
        sizeBytes = null,
    )

    private fun playable(episode: Episode, showArtworkUrl: String? = "https://art/show.jpg") =
        PlayableEpisode(
            episode = episode,
            showTitle = "Podlodka Podcast",
            showArtworkUrl = showArtworkUrl,
        )

    @Test
    fun `the media id is the episode id, not the audio url`() {
        // Publishers move audio without changing the episode; every write back keys off the id.
        val item = playable(episode()).toMediaItem()

        assertEquals("episode-1", item.mediaId)
        assertEquals("episode-1", item.episodeId)
        assertEquals("https://cdn.example.com/400.mp3", item.localConfiguration?.uri?.toString())
    }

    @Test
    fun `metadata carries the episode title and the show as artist`() {
        val metadata = playable(episode()).toMediaItem().mediaMetadata

        assertEquals("Podlodka #400", metadata.title)
        assertEquals("Podlodka Podcast", metadata.artist)
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, metadata.mediaType)
        assertEquals(3_600_000L, metadata.durationMs)
    }

    @Test
    fun `episode artwork wins over show artwork`() {
        val playable = playable(episode(artworkUrl = "https://art/episode.jpg"))

        assertEquals("https://art/episode.jpg", playable.artworkUrl)
        assertEquals(
            "https://art/episode.jpg",
            playable.toMediaItem().mediaMetadata.artworkUri?.toString(),
        )
    }

    @Test
    fun `an episode without artwork falls back to the show's`() {
        val playable = playable(episode(artworkUrl = null))

        assertEquals(
            "https://art/show.jpg",
            playable.toMediaItem().mediaMetadata.artworkUri?.toString(),
        )
    }

    @Test
    fun `an episode with no artwork anywhere maps to no artwork uri`() {
        val playable = playable(episode(artworkUrl = null), showArtworkUrl = null)

        assertNull(playable.toMediaItem().mediaMetadata.artworkUri)
    }

    @Test
    fun `the placeholder empty media item reports no episode`() {
        assertNull(MediaItem.EMPTY.episodeId)
    }
}

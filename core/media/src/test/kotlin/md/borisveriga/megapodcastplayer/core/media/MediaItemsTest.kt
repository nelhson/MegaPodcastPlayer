package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val item = checkNotNull(playable(episode()).toMediaItemOrNull())

        assertEquals("episode-1", item.mediaId)
        assertEquals("episode-1", item.episodeId)
        assertEquals("https://cdn.example.com/400.mp3", item.localConfiguration?.uri?.toString())
    }

    @Test
    fun `metadata carries the episode title and the show as artist`() {
        val metadata = checkNotNull(playable(episode()).toMediaItemOrNull()).mediaMetadata

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
            checkNotNull(playable.toMediaItemOrNull()).mediaMetadata.artworkUri?.toString(),
        )
    }

    @Test
    fun `an episode without artwork falls back to the show's`() {
        val playable = playable(episode(artworkUrl = null))

        assertEquals(
            "https://art/show.jpg",
            checkNotNull(playable.toMediaItemOrNull()).mediaMetadata.artworkUri?.toString(),
        )
    }

    @Test
    fun `an episode with no artwork anywhere maps to no artwork uri`() {
        val playable = playable(episode(artworkUrl = null), showArtworkUrl = null)

        assertNull(checkNotNull(playable.toMediaItemOrNull()).mediaMetadata.artworkUri)
    }

    @Test
    fun `the placeholder empty media item reports no episode`() {
        assertNull(MediaItem.EMPTY.episodeId)
    }

    @Test
    fun `an episode whose audio url is not http never becomes a media item`() {
        // Defence in depth behind the parser guard: whatever reaches the database, none of this
        // may reach the player. Each scheme here is one DefaultDataSource.Factory resolves.
        val hostile = listOf(
            "file:///data/data/md.borisveriga.megapodcastplayer/databases/megapodcastplayer.db",
            "content://com.other.app.provider/secret",
            "asset:///bundled.mp3",
            "rtmp://stream.example.com/live",
        )

        for (url in hostile) {
            val playable = playable(episode().copy(audioUrl = url))

            assertNull("$url should not be playable", playable.toMediaItemOrNull())
            assertFalse(playable.hasPlayableAudio)
        }
    }

    @Test
    fun `the youtube sentinel is still playable`() {
        // The sentinel is minted internally and intercepted before Media3 resolves it, so the
        // allowlist must not mistake it for a hostile scheme.
        val playable = playable(episode().copy(audioUrl = youTubeAudioSentinel("niTJ2221aS8")))

        assertTrue(playable.hasPlayableAudio)
        assertEquals(
            "youtube://video/niTJ2221aS8",
            checkNotNull(playable.toMediaItemOrNull()).localConfiguration?.uri?.toString(),
        )
    }

    @Test
    fun `hostile artwork is dropped without dropping the episode`() {
        // Artwork resolves through Coil, which knows file: and content: too. Losing the image costs
        // a glyph; losing the episode would cost the user something they asked for.
        val playable = playable(episode(artworkUrl = "content://com.other.app/pictures/1"))
        val item = checkNotNull(playable.toMediaItemOrNull())

        assertNull(item.mediaMetadata.artworkUri)
        assertEquals("episode-1", item.mediaId)
    }
}

package md.borisveriga.bpodcat.core.media

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Converts an episode into the [MediaItem] the player, the notification and the lock screen share.
 *
 * The `mediaId` is the episode id — not the audio URL — because a publisher can move the audio
 * without changing the episode, and every write back to the database (position, played state) keys
 * off the id.
 */
fun PlayableEpisode.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(episode.id)
    .setUri(episode.audioUrl)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(showTitle)
            .setAlbumTitle(showTitle)
            .setArtworkUri(artworkUrl?.toUri())
            .setDurationMs(episode.durationMs)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build(),
    )
    .build()

/** The episode id carried by a [MediaItem], or null for the placeholder empty item. */
val MediaItem.episodeId: String? get() = mediaId.takeIf { it.isNotEmpty() }

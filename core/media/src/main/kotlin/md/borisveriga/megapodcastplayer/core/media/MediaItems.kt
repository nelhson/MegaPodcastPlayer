package md.borisveriga.megapodcastplayer.core.media

import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import md.borisveriga.megapodcastplayer.core.model.isPlayableMediaUrl

/** Tag for the one thing this file logs: a URL the parsers should already have rejected. */
private const val TAG = "MediaItems"

/**
 * Whether this episode's audio URL may be handed to the player.
 *
 * The feed parsers are the primary gate ([isPlayableMediaUrl]); this is the second one. It exists
 * because the database can outlive the code that wrote it: a library populated before the scheme
 * allowlist shipped may still hold a `file:` or `content:` URL, and while `MIGRATION_2_3` sweeps
 * those out, a check at the point of use is what makes the guarantee independent of the migration
 * having run.
 */
val PlayableEpisode.hasPlayableAudio: Boolean
    get() = isPlayableMediaUrl(episode.audioUrl)

/**
 * Converts an episode into the [MediaItem] the player, the notification and the lock screen share,
 * or `null` when its audio URL is not one the player may resolve.
 *
 * The `mediaId` is the episode id — not the audio URL — because a publisher can move the audio
 * without changing the episode, and every write back to the database (position, played state) keys
 * off the id.
 *
 * Artwork is filtered by the same allowlist. It is a smaller problem than audio — the image loader
 * renders rather than exfiltrates — but `file:` and `content:` resolve there too, and dropping the
 * artwork costs a glyph instead of the episode.
 *
 * @return the media item, or `null` if the episode must not be played. Callers drop it from the
 *   queue; there is nothing a user could do about it and nothing worth interrupting them for.
 */
fun PlayableEpisode.toMediaItemOrNull(): MediaItem? {
    if (!hasPlayableAudio) {
        // Reaching here means a row predates the parser guard, or the guard has a hole. Either way
        // the URL itself is not logged: it is attacker-controlled text.
        Log.w(TAG, "Refusing to play episode ${episode.id}: audio URL scheme is not allowed")
        return null
    }
    return MediaItem.Builder()
        .setMediaId(episode.id)
        .setUri(episode.audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(showTitle)
                .setAlbumTitle(showTitle)
                .setArtworkUri(artworkUrl?.takeIf(::isPlayableMediaUrl)?.toUri())
                .setDurationMs(episode.durationMs)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .build(),
        )
        .build()
}

/** The episode id carried by a [MediaItem], or null for the placeholder empty item. */
val MediaItem.episodeId: String? get() = mediaId.takeIf { it.isNotEmpty() }

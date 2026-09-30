package md.borisveriga.megapodcastplayer.core.media.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoOnlyRefOrNull

/**
 * How a downloaded picture is told apart from a downloaded sound in Media3's index.
 *
 * An episode's audio download has the episode id as its content id, which is what everything that
 * mirrors downloads into the episodes table reads. Its picture is a second download of the same
 * episode, so it needs a second id, and the id has to say which episode it belongs to without a
 * table to look it up in: the episode id with this suffix. Episode ids are SHA-1 hex, so no audio
 * download's id can ever end this way.
 */
private const val VIDEO_DOWNLOAD_SUFFIX = "#video"

/**
 * The content id of [episodeId]'s picture download.
 *
 * One per episode, whatever the rendition: an episode keeps its picture at one height at a time,
 * and a new height replaces the old one rather than sitting beside it.
 *
 * @param episodeId the episode.
 */
internal fun videoDownloadId(episodeId: String): String = episodeId + VIDEO_DOWNLOAD_SUFFIX

/**
 * The episode a picture download belongs to.
 *
 * @param downloadId any content id in the index.
 * @return the episode id, or null when [downloadId] is an audio download's.
 */
internal fun episodeIdOfVideoDownloadOrNull(downloadId: String): String? =
    if (downloadId.endsWith(VIDEO_DOWNLOAD_SUFFIX)) {
        downloadId.removeSuffix(VIDEO_DOWNLOAD_SUFFIX).takeIf { it.isNotEmpty() }
    } else {
        null
    }

/** Whether this download holds an episode's picture rather than its sound. */
internal val Download.isVideoDownload: Boolean
    @OptIn(UnstableApi::class)
    get() = episodeIdOfVideoDownloadOrNull(request.id) != null

/**
 * Flattens a picture download into the episode it belongs to and a [VideoDownload].
 *
 * The rendition is read back off the request's URI, the video-only sentinel it was made with, so
 * the index holds the height in exactly one place and cannot disagree with itself.
 *
 * @return the pair, or null when this is an audio download, its URI is not a video-only sentinel,
 *   or it is on its way out — a download being removed is already "no picture" to the user.
 */
@OptIn(UnstableApi::class)
internal fun Download.asVideoDownloadOrNull(): Pair<String, VideoDownload>? {
    val episodeId = episodeIdOfVideoDownloadOrNull(request.id) ?: return null
    val quality = youTubeVideoOnlyRefOrNull(request.uri.toString())?.quality ?: return null
    val state = downloadStateOf(state)
    if (state == DownloadState.NOT_DOWNLOADED) return null
    return episodeId to VideoDownload(quality = quality, state = state, percent = percentOf(state))
}

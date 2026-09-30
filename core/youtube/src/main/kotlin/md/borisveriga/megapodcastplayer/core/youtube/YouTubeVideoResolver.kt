package md.borisveriga.megapodcastplayer.core.youtube

import androidx.annotation.WorkerThread
import java.io.IOException
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.model.VideoQuality

/**
 * Video resolution for YouTube videos: the picture, where [YouTubeAudioResolver] is the sound.
 *
 * YouTube stops publishing combined video-and-audio files above 360p, so anything sharper is a
 * *video-only* stream that the player plays alongside the audio stream it already has. This
 * interface hands out that video-only stream. Everything said about terms of service and fragility
 * in [YouTubeAudioResolver] applies here unchanged.
 */

/**
 * A YouTube video-only stream resolved to something that can be streamed right now.
 *
 * @property url a direct progressive URL carrying picture and no sound. Short-lived and IP-bound;
 *   never persisted.
 * @property expiresAt when [url] stops working.
 * @property quality the rendition actually chosen, which may differ from the one asked for when
 *   the video does not offer that height.
 * @property requestHeaders headers [url] must be fetched with.
 */
data class ResolvedYouTubeVideo(
    val url: String,
    val expiresAt: Instant,
    val quality: VideoQuality,
    val requestHeaders: Map<String, String>,
)

/** Turns a YouTube video id into a video-only URL that can be played right now. */
interface YouTubeVideoResolver {

    /**
     * Resolves the picture of [videoId] at, or as near as possible to, [preferred].
     *
     * Blocking, for the reason [YouTubeAudioResolver.resolve] gives: the caller is Media3's
     * resolver on a loader thread. Shares its cache with the audio side, so asking for both halves
     * of one video costs one extraction.
     *
     * @param videoId YouTube's video id, case-sensitive.
     * @param preferred the height the user asked for.
     * @return the resolved video-only stream.
     * @throws YouTubeVideoUnavailableException when the video has no picture we can play.
     * @throws YouTubeAudioUnavailableException when the video cannot be extracted at all.
     * @throws IOException on network failure.
     */
    @WorkerThread
    fun resolveVideo(videoId: String, preferred: VideoQuality): ResolvedYouTubeVideo

    /**
     * The rendition heights [videoId] offers as playable video-only streams, lowest first.
     *
     * Blocking like [resolveVideo]; a coroutine caller wraps it in `withContext(ioDispatcher)`.
     * Empty when the video has no playable picture at all, which is not an error: it is the answer
     * a quality picker shows.
     *
     * @param videoId YouTube's video id, case-sensitive.
     * @throws YouTubeAudioUnavailableException when the video cannot be extracted at all.
     * @throws IOException on network failure.
     */
    @WorkerThread
    fun availableQualities(videoId: String): List<VideoQuality>
}

/**
 * A video exists and has sound we can play, but no picture: every video stream it offers is a
 * manifest, a live rendition or otherwise not a plain URL.
 *
 * An [IOException] for the same reason [YouTubeAudioUnavailableException] is: it is thrown from
 * inside a Media3 data source, and Media3 already knows what to do with one.
 *
 * @param videoId the video that could not be resolved.
 * @param reason a short phrase for the user, completing "…because …".
 */
class YouTubeVideoUnavailableException(
    val videoId: String,
    val reason: String,
) : IOException("YouTube video unavailable for $videoId: $reason")

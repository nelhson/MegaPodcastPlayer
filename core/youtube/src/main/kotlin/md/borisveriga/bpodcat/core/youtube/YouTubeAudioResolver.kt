package md.borisveriga.bpodcat.core.youtube

import androidx.annotation.WorkerThread
import java.io.IOException
import java.time.Instant

/**
 * Audio resolution for YouTube videos.
 *
 * Everything in this module exists because of one mismatch: the app stores a durable audio URL per
 * episode, and YouTube has no such thing. A `googlevideo.com` URL expires within hours and is bound
 * to the IP that asked for it, so what is stored is a
 * [md.borisveriga.bpodcat.core.model.youTubeAudioSentinel] and the real URL is fetched at the moment
 * the bytes are needed.
 *
 * A note on how this is obtained: resolution works by extracting YouTube's own player response,
 * which is not something YouTube's terms of service permit. That is a deliberate, accepted trade-off
 * for a sideloaded personal build; it is not compatible with distribution on Google Play. It also
 * means this module will break, without warning, whenever YouTube changes its player — which is why
 * [YouTubeAudioUnavailableException] carries a readable reason rather than letting a generic
 * playback error reach the user.
 */

/**
 * A YouTube audio track resolved to something that can be streamed right now.
 *
 * @property url a direct progressive audio URL. Short-lived and IP-bound; never persisted.
 * @property expiresAt when [url] stops working.
 * @property durationMs the video's duration, which the playlist Atom feed never publishes.
 * @property requestHeaders headers [url] must be fetched with.
 */
data class ResolvedYouTubeAudio(
    val url: String,
    val expiresAt: Instant,
    val durationMs: Long?,
    val requestHeaders: Map<String, String>,
)

/** Turns a YouTube video id into an audio URL that can be played right now. */
interface YouTubeAudioResolver {

    /**
     * Resolves [videoId], serving a cached result while it is still valid.
     *
     * **Blocking, and deliberately not a suspend function.** Its only caller is Media3's
     * `ResolvingDataSource.Resolver`, which runs on a loader or download thread and is documented as
     * allowed to block; making this suspend would force a `runBlocking` on the hot path in exchange
     * for nothing. Any future coroutine caller should wrap it in `withContext(ioDispatcher)`.
     *
     * @param videoId YouTube's video id, case-sensitive.
     * @return the resolved audio track.
     * @throws YouTubeAudioUnavailableException when the video has no audio we can play.
     * @throws IOException on network failure.
     */
    @WorkerThread
    fun resolve(videoId: String): ResolvedYouTubeAudio
}

/**
 * A video exists but has no audio this user can play: private, deleted, members-only,
 * age-restricted, geo-blocked, or a live stream.
 *
 * Extends [IOException] for two reasons. `ResolvingDataSource.Resolver.resolveDataSpec` is declared
 * to throw nothing else, and Media3 already knows what to do with an `IOException` — it becomes a
 * retryable download failure and a `PlaybackException` the UI can already display.
 *
 * @param videoId the video that could not be resolved.
 * @param reason a short phrase for the user, completing "…because …".
 * @param cause the extractor failure this was translated from, if any.
 */
class YouTubeAudioUnavailableException(
    val videoId: String,
    val reason: String,
    cause: Throwable? = null,
) : IOException("YouTube audio unavailable for $videoId: $reason", cause)

package md.borisveriga.megapodcastplayer.core.youtube

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.network.di.MegaPodcastPlayerOkHttp
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Initialises NewPipe exactly once per process.
 *
 * `NewPipe.init` writes process-global state, so calling it twice is at best wasteful. Initialising
 * lazily from the resolver rather than eagerly from the Application class matters: the download
 * service can bring the process up on its own after a reboot, and this way nothing outside this
 * module has to know YouTube support exists at all.
 *
 * @property client the shared OkHttp client the extractor will run its requests through.
 */
@Singleton
internal class NewPipeBootstrap @Inject constructor(
    @param:MegaPodcastPlayerOkHttp private val client: OkHttpClient,
) {
    private val initialised = AtomicBoolean(false)

    /** Initialises NewPipe if it has not been initialised yet. Safe to call from any thread. */
    fun ensureInitialised() {
        if (initialised.compareAndSet(false, true)) {
            NewPipe.init(OkHttpNewPipeDownloader(client))
        }
    }
}

/**
 * Resolves YouTube audio — and, since the player learned to show the picture, YouTube video — with
 * NewPipeExtractor.
 *
 * One class for both halves because they come from one extraction: YouTube's player response
 * lists every rendition of a video at once, and the audio and the video-only streams the player
 * merges expire together. Extracting twice for the two halves would double the cost of the slowest
 * step and let the halves fall out of step on invalidation.
 *
 * @property bootstrap one-time NewPipe initialisation.
 * @property clock injected so cache expiry is deterministic in tests.
 * @property decoders says which picture codecs this device can decode, so that a rendition it
 *   cannot is never the one chosen.
 */
@Singleton
internal class NewPipeAudioResolver @Inject constructor(
    private val bootstrap: NewPipeBootstrap,
    private val clock: Clock,
    private val decoders: VideoDecoderCheck,
) : YouTubeAudioResolver, YouTubeVideoResolver {

    /**
     * Serialises extraction.
     *
     * Two reasons, either sufficient. Three downloads run in parallel, and without this a queued
     * show would extract the same video three times. And NewPipe's YouTube extractor caches the
     * deciphered player JavaScript in process-global state whose thread safety is not guaranteed.
     * Extraction takes roughly half a second to two seconds, so serialising costs little.
     */
    private val extractionLock = Any()

    /**
     * Extractions, keyed by video id, guarded by [extractionLock].
     *
     * Not an optimisation but a requirement: one download issues many `open()` calls as it resumes
     * and retries, and one playback re-opens on every seek — twice over when the picture is
     * showing, once per half. Media3's own `ResolvingDataSource` documentation asks resolvers to
     * cache for exactly this reason.
     */
    private val cache = LinkedHashMap<String, ExtractedStreams>()

    override fun resolve(videoId: String): ResolvedYouTubeAudio = streamsOf(videoId).audio

    override fun resolveVideo(videoId: String, preferred: VideoQuality): ResolvedYouTubeVideo {
        val streams = streamsOf(videoId)
        val chosen = selectVideoCandidate(streams.video, preferred)
            ?: throw YouTubeVideoUnavailableException(videoId, "no playable picture for this video")
        return ResolvedYouTubeVideo(
            url = chosen.url,
            expiresAt = streams.expiresAt,
            quality = VideoQuality(chosen.height),
            requestHeaders = streams.audio.requestHeaders,
        )
    }

    override fun availableQualities(videoId: String): List<VideoQuality> =
        streamsOf(videoId).video
            .map { it.height }
            .distinct()
            .sorted()
            .map(::VideoQuality)

    /**
     * The extraction for [videoId], served from the cache while it is fresh.
     *
     * The one place the lock is taken for a read, so that [resolve], [resolveVideo] and
     * [availableQualities] cannot disagree about what a video offers.
     */
    private fun streamsOf(videoId: String): ExtractedStreams {
        val now = Instant.now(clock)

        synchronized(extractionLock) {
            cache[videoId]?.let { cached ->
                if (cached.isFresh(now)) return cached
                cache.remove(videoId)
            }

            val extracted = extract(videoId, now)
            cache[videoId] = extracted
            // Bounded so a long listening session cannot grow this without limit. Insertion order,
            // so the oldest extraction is the one dropped.
            while (cache.size > MAX_CACHED_RESOLUTIONS) {
                cache.remove(cache.keys.first())
            }
            return extracted
        }
    }

    override fun invalidate(videoId: String) {
        // Under the same lock as `resolve`, so a resolution in flight either completes before this
        // removes it or starts after — never half of each. Dropping a resolution that has just been
        // replaced by a good one costs a single extra extraction, which is the cheaper mistake.
        synchronized(extractionLock) {
            cache.remove(videoId)
        }
    }

    /** Runs the extractor and translates its failures into something a user can read. */
    private fun extract(videoId: String, now: Instant): ExtractedStreams {
        bootstrap.ensureInitialised()

        val info = try {
            StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
        } catch (e: ReCaptchaException) {
            throw YouTubeAudioUnavailableException(
                videoId, "YouTube is asking for a captcha; try again in a few minutes", e,
            )
        } catch (e: PrivateContentException) {
            throw YouTubeAudioUnavailableException(videoId, "the video is private", e)
        } catch (e: AgeRestrictedContentException) {
            throw YouTubeAudioUnavailableException(videoId, "the video is age restricted", e)
        } catch (e: GeographicRestrictionException) {
            throw YouTubeAudioUnavailableException(
                videoId, "the video is not available in this country", e,
            )
        } catch (e: PaidContentException) {
            throw YouTubeAudioUnavailableException(videoId, "the video requires payment", e)
        } catch (e: YoutubeMusicPremiumContentException) {
            throw YouTubeAudioUnavailableException(
                videoId, "the video requires a YouTube Music Premium account", e,
            )
        } catch (e: SoundCloudGoPlusContentException) {
            throw YouTubeAudioUnavailableException(videoId, "the track requires a paid account", e)
        } catch (e: AccountTerminatedException) {
            throw YouTubeAudioUnavailableException(videoId, "the uploader's account is gone", e)
        } catch (e: ContentNotAvailableException) {
            // The base class: covers deleted, unlisted-turned-private and members-only videos.
            throw YouTubeAudioUnavailableException(
                videoId, "the video was removed or made private", e,
            )
        } catch (e: ExtractionException) {
            // Almost always means YouTube changed its player and the extractor needs updating.
            throw YouTubeAudioUnavailableException(
                videoId, "YouTube changed something this app cannot read yet", e,
            )
        }
        // A plain IOException is deliberately not caught: it is genuinely transient, and letting the
        // original through keeps Media3's retry ladder and the network diagnostics intact.

        if (info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
        ) {
            // Live content exposes only HLS, while the sentinel URI routes to Media3's progressive
            // downloader. Failing here is far clearer than failing at the first byte.
            throw YouTubeAudioUnavailableException(videoId, "live streams cannot be played as audio")
        }

        val stream = selectAudioStream(info.audioStreams.orEmpty())
            ?: throw YouTubeAudioUnavailableException(videoId, "no downloadable audio track")

        val url = stream.content
        val audio = ResolvedYouTubeAudio(
            url = url,
            expiresAt = expiryOf(url, now),
            // getDuration() is seconds. Carried so the caller can fill in what the feed omits.
            durationMs = info.duration.takeIf { it > 0L }?.times(1000L),
            requestHeaders = mapOf("User-Agent" to YOUTUBE_USER_AGENT),
        )
        // The picture is optional here: a video with sound and no playable picture is still an
        // episode. Only `resolveVideo` turns an empty list into a failure. Filtered by what the
        // device can decode once, here, so the renditions offered and the one chosen agree.
        val video = decodableVideoCandidates(
            playableVideoCandidates(info.videoOnlyStreams.orEmpty()),
            decoders,
        )
        return ExtractedStreams(
            audio = audio,
            video = video,
            // Every stream comes from one player response and states its own deadline; the snapshot
            // dies with the first of them so no half is ever served past its expiry.
            expiresAt = (video.map { expiryOf(it.url, now) } + audio.expiresAt).min(),
        )
    }

    /**
     * One extraction: the sound, the playable renditions of the picture, and when both stop working.
     *
     * The video candidates are this module's own type rather than the extractor's, so that nothing
     * `org.schabi` leaks past the class that made the call.
     *
     * @property audio the chosen audio stream.
     * @property video every playable video-only rendition, unranked.
     * @property expiresAt the earliest deadline any of the URLs above carries.
     */
    private class ExtractedStreams(
        val audio: ResolvedYouTubeAudio,
        val video: List<VideoCandidate>,
        val expiresAt: Instant,
    ) {
        /** Whether these URLs will still work for long enough to be worth reusing. */
        fun isFresh(now: Instant): Boolean = expiresAt.minus(EXPIRY_SKEW).isAfter(now)
    }

    private companion object {
        /**
         * How long before its stated expiry a URL is treated as already dead.
         *
         * A download that starts four minutes before expiry and takes five would fail halfway with
         * a 403, and Media3 would retry against the same stale URL. Re-extracting early is cheap.
         */
        val EXPIRY_SKEW: Duration = Duration.ofMinutes(5)

        /** Enough for a long queue; small enough that the map is never worth thinking about. */
        const val MAX_CACHED_RESOLUTIONS = 64
    }
}

/**
 * Picks the audio track to play.
 *
 * Three filters, in order of how badly getting them wrong hurts.
 *
 * **Track type comes first.** YouTube increasingly ships auto-dubbed tracks alongside the original,
 * and the extractor lists them all. Left unfiltered, the choice is effectively arbitrary — the first
 * device run of this code downloaded a German auto-dub of an English talk. Anything but
 * [AudioTrackType.ORIGINAL] is therefore discarded outright rather than merely ranked lower: a
 * machine translation of the wrong language is not a worse version of the episode, it is the wrong
 * episode. Streams with no declared track type are kept, since most videos have exactly one track
 * and declare nothing.
 *
 * **Then delivery method.** Only progressive HTTP is eligible: the sentinel URI carries no container
 * hint, so Media3 builds a `ProgressiveMediaSource` for it, and a DASH or HLS manifest handed to
 * that would fail at the first byte.
 *
 * **Then format and bitrate.** M4A (AAC) beats WebM/Opus because ExoPlayer seeks it far more
 * reliably, and seeking is what a listener does constantly in an hour-long talk. Bitrate is the
 * highest available at or below [MAX_BITRATE_KBPS]: these are hour-plus videos going through the
 * same never-evicted download cache as podcasts, and the difference between 128 and 256 kbps is
 * inaudible for speech while doubling what a downloaded episode costs on disk.
 *
 * Extracted as a top-level function so every one of those choices is testable without a network.
 *
 * @param streams the audio streams the extractor reported.
 * @return the stream to play, or `null` when none is usable.
 */
internal fun selectAudioStream(streams: List<AudioStream>): AudioStream? {
    val playable = streams.filter { stream ->
        stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            stream.isUrl &&
            !stream.content.isNullOrBlank() &&
            // null means the video declares no track types at all, which is the common case.
            (stream.audioTrackType == null || stream.audioTrackType == AudioTrackType.ORIGINAL)
    }
    if (playable.isEmpty()) return null

    val withinBudget = playable.filter { it.bitrateKbps() in 1..MAX_BITRATE_KBPS }

    return if (withinBudget.isNotEmpty()) {
        // The best track we are willing to pay for: M4A first, then as much bitrate as the ceiling
        // allows.
        withinBudget.maxWith(
            compareBy<AudioStream> { if (it.format == MediaFormat.M4A) 1 else 0 }
                .thenBy { it.bitrateKbps() },
        )
    } else {
        // Everything on offer exceeds the ceiling. Take the cheapest rather than refuse to play,
        // still preferring M4A between two of equal size. A reported bitrate of zero means the
        // extractor did not know, which is not a reason to prefer that track.
        playable.minWith(
            compareBy<AudioStream> { it.bitrateKbps().takeIf { rate -> rate > 0 } ?: Int.MAX_VALUE }
                .thenBy { if (it.format == MediaFormat.M4A) 0 else 1 },
        )
    }
}

/**
 * A video-only rendition, reduced to what choosing between renditions needs.
 *
 * Deliberately not a [VideoStream]: this is what the resolver caches, and the cache must not hold
 * extractor types any more than the API may expose them.
 *
 * @property url the direct URL of the stream.
 * @property height the frame height in pixels.
 * @property fps the frame rate, or `0` when the extractor does not say.
 * @property isMp4 whether the container is MP4, the one the audio it is merged with is in.
 * @property codec the codec family the picture is encoded in; the container does not say, since
 *   YouTube ships both H.264 and AV1 as MP4.
 */
internal data class VideoCandidate(
    val url: String,
    val height: Int,
    val fps: Int,
    val isMp4: Boolean,
    val codec: VideoCodec = VideoCodec.UNKNOWN,
)

/**
 * Reduces the extractor's video-only streams to the ones the player can use.
 *
 * Two filters. **Delivery first**: only a progressive HTTP URL can be handed to the progressive
 * media source the sentinel routes to — a DASH or HLS manifest, or an OTF stream that is a template
 * rather than a URL, would fail at the first byte. **Then video-only**: a combined stream carries its
 * own sound, and merging it with the audio stream the player already has would play two soundtracks.
 * Height must be known, because it is the whole basis of the choice.
 *
 * Extracted as a top-level function so it can be tested without a network.
 *
 * @param streams the video-only streams the extractor reported.
 * @return the usable renditions, in the extractor's order.
 */
internal fun playableVideoCandidates(streams: List<VideoStream>): List<VideoCandidate> =
    streams.filter { stream ->
        stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            stream.isUrl &&
            !stream.content.isNullOrBlank() &&
            // The method, not the property: `VideoStream` exposes `isVideoOnly` as a public field
            // as well as a getter, and Kotlin's property syntax would read the field.
            stream.isVideoOnly() &&
            stream.height > 0
    }.map { stream ->
        VideoCandidate(
            url = stream.content,
            height = stream.height,
            fps = stream.fps,
            isMp4 = stream.format == MediaFormat.MPEG_4,
            codec = videoCodecOf(stream.codec),
        )
    }

/**
 * Picks the rendition to show for a requested height.
 *
 * In order of preference: the exact height asked for; the tallest below it that is still at least
 * [VideoQuality.PREFERRED_MIN_HEIGHT]; the shortest above it; and, when the video offers nothing
 * that tall at all, the best it has — an old 480p upload plays at 480p rather than refusing. Among
 * renditions of one height, the codec decides first, in [VideoCodec]'s order — H.264 before VP9
 * before AV1, cheapest to decode first; then MP4 wins over WebM because the audio it is merged with
 * is already in an MP4 container; then the lower frame rate, because a talking head at 60 frames a
 * second is twice the data for nothing anyone can see.
 *
 * Extracted as a top-level function so every one of those choices is testable without a network.
 *
 * @param candidates the playable renditions, from [playableVideoCandidates].
 * @param preferred the height the user asked for.
 * @return the rendition to play, or `null` when there is none.
 */
internal fun selectVideoCandidate(
    candidates: List<VideoCandidate>,
    preferred: VideoQuality,
): VideoCandidate? {
    if (candidates.isEmpty()) return null
    val target = preferred.height

    val exact = candidates.filter { it.height == target }
    val belowButSharpEnough = candidates
        .filter { it.height in VideoQuality.PREFERRED_MIN_HEIGHT until target }
    val above = candidates.filter { it.height > target }

    val chosenHeight = when {
        exact.isNotEmpty() -> target
        belowButSharpEnough.isNotEmpty() -> belowButSharpEnough.maxOf { it.height }
        above.isNotEmpty() -> above.minOf { it.height }
        else -> candidates.maxOf { it.height }
    }
    return candidates
        .filter { it.height == chosenHeight }
        .minWith(
            compareBy<VideoCandidate> { it.codec.ordinal }
                .thenBy { if (it.isMp4) 0 else 1 }
                .thenBy { it.fps },
        )
}

/**
 * This stream's average bitrate in kilobits per second.
 *
 * The extractor reports YouTube audio bitrates in kbps — a real playlist yields values like 48, 128
 * and 160 — but that is a property of its itag table rather than a documented guarantee, and other
 * services report bits per second. Comparing a raw value against a kbps ceiling silently disabled
 * the ceiling entirely the first time this ran against real data, so the unit is normalised here
 * rather than assumed: no speech audio track is 1000 kbps, and none is 1000 bps, which makes the
 * threshold unambiguous.
 */
private fun AudioStream.bitrateKbps(): Int =
    if (averageBitrate >= BPS_THRESHOLD) averageBitrate / 1000 else averageBitrate

/** Above this, a reported bitrate must be bits per second rather than kilobits. */
private const val BPS_THRESHOLD = 1000

/**
 * Works out when a resolved media URL stops working.
 *
 * `googlevideo.com` URLs carry their own deadline in an `expire` query parameter, as Unix seconds.
 * When it is missing or unreadable, an hour is assumed — short enough to re-extract well before the
 * typical six-hour lifetime runs out, long enough that a single listen does not re-extract.
 *
 * Extracted as a top-level function so it can be tested without a network.
 *
 * @param url the resolved media URL.
 * @param now the current instant.
 */
internal fun expiryOf(url: String, now: Instant): Instant {
    val raw = Regex("""[?&]expire=(\d+)""").find(url)?.groupValues?.get(1)
    val seconds = raw?.toLongOrNull() ?: return now.plus(FALLBACK_VALIDITY)
    return runCatching { Instant.ofEpochSecond(seconds) }.getOrElse { now.plus(FALLBACK_VALIDITY) }
}

/** Ceiling for the chosen audio track, in kbps. Speech is indistinguishable well below this. */
private const val MAX_BITRATE_KBPS = 160

/** Assumed validity when a URL states no expiry of its own. */
private val FALLBACK_VALIDITY: Duration = Duration.ofHours(1)

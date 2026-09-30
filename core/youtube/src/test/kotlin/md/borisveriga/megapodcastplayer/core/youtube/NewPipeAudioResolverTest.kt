package md.borisveriga.megapodcastplayer.core.youtube

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Tests the decisions inside the resolver that can be made without a network.
 *
 * Extraction itself is untestable offline and deliberately untested: a test that reached YouTube
 * would be a flaky dependency on their live infrastructure, and would fail for reasons that have
 * nothing to do with this code. What *is* worth pinning down is the stream choice — which decides
 * how much disk every downloaded video costs and whether it seeks properly — and the expiry
 * arithmetic, which decides whether a long download survives to the end.
 *
 * The cache tests at the bottom are the exception, and stub `StreamInfo.getInfo` rather than reach
 * the network. They are not testing extraction; they are counting how often it happens, which is the
 * only way to show that a resolution really is reused and that invalidating one really does force
 * another.
 */
class NewPipeAudioResolverTest {

    private fun audioStream(
        bitrate: Int,
        format: MediaFormat = MediaFormat.M4A,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        isUrl: Boolean = true,
        content: String = "https://rr3.googlevideo.com/videoplayback?itag=140",
        trackType: AudioTrackType? = null,
    ): AudioStream = mockk(relaxed = true) {
        every { averageBitrate } returns bitrate
        every { getFormat() } returns format
        every { deliveryMethod } returns delivery
        every { isUrl() } returns isUrl
        every { getContent() } returns content
        every { audioTrackType } returns trackType
    }

    private fun videoStream(
        h: Int,
        frameRate: Int = 30,
        format: MediaFormat = MediaFormat.MPEG_4,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        isUrl: Boolean = true,
        content: String = "https://rr3.googlevideo.com/videoplayback?itag=136&expire=2000000000",
        videoOnly: Boolean = true,
    ): VideoStream = mockk(relaxed = true) {
        every { height } returns h
        every { fps } returns frameRate
        every { getFormat() } returns format
        every { deliveryMethod } returns delivery
        every { isUrl() } returns isUrl
        every { getContent() } returns content
        every { isVideoOnly() } returns videoOnly
    }

    private fun candidate(height: Int, fps: Int = 30, mp4: Boolean = true) = VideoCandidate(
        url = "https://rr3.googlevideo.com/videoplayback?h=$height&fps=$fps&mp4=$mp4",
        height = height,
        fps = fps,
        isMp4 = mp4,
    )

    // --- the resolution cache ---------------------------------------------

    /**
     * Runs [block] against a resolver whose extractor is stubbed out.
     *
     * The stub always succeeds and always returns the same video, so the only thing that varies
     * between these tests is how many times it was asked — which is exactly the question.
     *
     * @param videoOnly the picture renditions the stubbed video offers; by default 720p and 1080p
     *   in MP4 and a WebM 720p, which is the shape of an ordinary upload.
     */
    private fun withStubbedExtractor(
        videoOnly: List<VideoStream> = listOf(
            videoStream(h = 1080),
            videoStream(h = 720, format = MediaFormat.WEBM),
            videoStream(h = 720),
        ),
        block: (NewPipeAudioResolver) -> Unit,
    ) {
        mockkStatic(StreamInfo::class)
        try {
            val info: StreamInfo = mockk(relaxed = true) {
                every { streamType } returns StreamType.VIDEO_STREAM
                every { audioStreams } returns listOf(audioStream(bitrate = 128, content = URL))
                every { videoOnlyStreams } returns videoOnly
                every { duration } returns 3600L
            }
            every { StreamInfo.getInfo(any<String>()) } returns info

            block(NewPipeAudioResolver(mockk(relaxed = true), CLOCK))
        } finally {
            unmockkStatic(StreamInfo::class)
        }
    }

    @Test
    fun `reuses a resolution that has not expired`() = withStubbedExtractor { resolver ->
        // One download issues many opens as it chunks, resumes and retries, and one playback
        // re-opens on every seek. Extracting for each of those would be unusable.
        resolver.resolve(VIDEO_ID)
        resolver.resolve(VIDEO_ID)

        verify(exactly = 1) { StreamInfo.getInfo(any<String>()) }
    }

    @Test
    fun `invalidate forces the next resolve to extract again`() = withStubbedExtractor { resolver ->
        // The point of the whole mechanism: a googlevideo URL is bound to the IP that asked for it,
        // so it can stop working while its stated expiry is still hours away. Without this, every
        // retry replays the dead URL and the download fails over something one extraction fixes.
        resolver.resolve(VIDEO_ID)
        resolver.invalidate(VIDEO_ID)
        resolver.resolve(VIDEO_ID)

        verify(exactly = 2) { StreamInfo.getInfo(any<String>()) }
    }

    @Test
    fun `invalidate leaves every other video alone`() = withStubbedExtractor { resolver ->
        resolver.resolve(VIDEO_ID)
        resolver.resolve(OTHER_VIDEO_ID)
        resolver.invalidate(VIDEO_ID)
        resolver.resolve(OTHER_VIDEO_ID)

        // Two extractions, both from the first pair: the second video is still cached.
        verify(exactly = 2) { StreamInfo.getInfo(any<String>()) }
    }

    @Test
    fun `invalidating a video that was never resolved does nothing`() =
        withStubbedExtractor { resolver ->
            resolver.invalidate(VIDEO_ID)
            resolver.resolve(VIDEO_ID)

            verify(exactly = 1) { StreamInfo.getInfo(any<String>()) }
        }

    // --- the picture shares the sound's extraction ------------------------

    @Test
    fun `the picture and the sound come from one extraction`() = withStubbedExtractor { resolver ->
        // The video screen asks for all three within a second of opening. Three extractions
        // would triple the slowest step and let the halves expire on different clocks.
        resolver.resolve(VIDEO_ID)
        resolver.resolveVideo(VIDEO_ID, VideoQuality.DEFAULT)
        resolver.availableQualities(VIDEO_ID)

        verify(exactly = 1) { StreamInfo.getInfo(any<String>()) }
    }

    @Test
    fun `invalidate drops the picture along with the sound`() = withStubbedExtractor { resolver ->
        resolver.resolveVideo(VIDEO_ID, VideoQuality.DEFAULT)
        resolver.invalidate(VIDEO_ID)
        resolver.resolveVideo(VIDEO_ID, VideoQuality.DEFAULT)

        verify(exactly = 2) { StreamInfo.getInfo(any<String>()) }
    }

    @Test
    fun `available qualities lists each height once, lowest first`() =
        withStubbedExtractor { resolver ->
            assertEquals(
                listOf(VideoQuality(720), VideoQuality(1080)),
                resolver.availableQualities(VIDEO_ID),
            )
        }

    @Test
    fun `resolving the picture reports the height it settled on`() =
        withStubbedExtractor { resolver ->
            val video = resolver.resolveVideo(VIDEO_ID, VideoQuality(4320))

            assertEquals(VideoQuality(1080), video.quality)
            assertEquals("TestAgent/1.0".isEmpty(), video.requestHeaders.isEmpty())
        }

    @Test
    fun `a video with sound and no playable picture fails readably`() =
        withStubbedExtractor(videoOnly = emptyList()) { resolver ->
            // The sound is still an episode, so `resolve` must keep working…
            resolver.resolve(VIDEO_ID)
            assertEquals(emptyList<VideoQuality>(), resolver.availableQualities(VIDEO_ID))

            // …and only the request for the picture fails, with a reason the screen can show.
            assertThrows(YouTubeVideoUnavailableException::class.java) {
                resolver.resolveVideo(VIDEO_ID, VideoQuality.DEFAULT)
            }
        }

    // --- playableVideoCandidates ------------------------------------------

    @Test
    fun `keeps only progressive video-only urls`() {
        val dash = videoStream(h = 1080, delivery = DeliveryMethod.DASH)
        val manifest = videoStream(h = 1080, isUrl = false)
        val blank = videoStream(h = 1080, content = "")
        val combined = videoStream(h = 360, videoOnly = false)
        val unknownHeight = videoStream(h = 0)
        val good = videoStream(h = 720)

        val candidates = playableVideoCandidates(
            listOf(dash, manifest, blank, combined, unknownHeight, good),
        )

        assertEquals(listOf(candidate(720)), candidates.map { candidate(it.height, it.fps, it.isMp4) })
    }

    @Test
    fun `records the container and the frame rate`() {
        val webm60 = videoStream(h = 720, frameRate = 60, format = MediaFormat.WEBM)

        val candidate = playableVideoCandidates(listOf(webm60)).single()

        assertEquals(60, candidate.fps)
        assertEquals(false, candidate.isMp4)
    }

    // --- selectVideoCandidate ---------------------------------------------

    @Test
    fun `the exact height wins`() {
        val choice = selectVideoCandidate(
            listOf(candidate(480), candidate(720), candidate(1080)),
            VideoQuality(720),
        )

        assertEquals(720, choice?.height)
    }

    @Test
    fun `falls back to the tallest below the request while it is still sharp enough`() {
        val choice = selectVideoCandidate(
            listOf(candidate(480), candidate(720), candidate(1440)),
            VideoQuality(1080),
        )

        assertEquals(720, choice?.height)
    }

    @Test
    fun `goes above the request rather than below the floor`() {
        // 480p is closer to 720p than 1080p is, but 480p is the thing the floor exists to avoid.
        val choice = selectVideoCandidate(
            listOf(candidate(480), candidate(1080)),
            VideoQuality(720),
        )

        assertEquals(1080, choice?.height)
    }

    @Test
    fun `settles for the best there is when nothing reaches the floor`() {
        // An old upload. Refusing to play it would be worse than playing it as it is.
        val choice = selectVideoCandidate(
            listOf(candidate(240), candidate(480), candidate(360)),
            VideoQuality(720),
        )

        assertEquals(480, choice?.height)
    }

    @Test
    fun `prefers mp4 at the chosen height`() {
        val webm = candidate(720, mp4 = false)
        val mp4 = candidate(720, mp4 = true)

        assertSame(mp4, selectVideoCandidate(listOf(webm, mp4), VideoQuality(720)))
    }

    @Test
    fun `prefers the lower frame rate at the chosen height`() {
        // Twice the data for a talking head nobody can see move any faster.
        val sixty = candidate(720, fps = 60)
        val thirty = candidate(720, fps = 30)

        assertSame(thirty, selectVideoCandidate(listOf(sixty, thirty), VideoQuality(720)))
    }

    @Test
    fun `returns null when there is no picture at all`() {
        assertNull(selectVideoCandidate(emptyList(), VideoQuality.DEFAULT))
    }

    // --- selectAudioStream ------------------------------------------------

    @Test
    fun `prefers m4a over webm at the same bitrate`() {
        // ExoPlayer seeks AAC in an MP4 container far more reliably than Opus in WebM, and seeking
        // is constant in an hour-long talk.
        val webm = audioStream(bitrate = 128, format = MediaFormat.WEBMA_OPUS)
        val m4a = audioStream(bitrate = 128, format = MediaFormat.M4A)

        assertSame(m4a, selectAudioStream(listOf(webm, m4a)))
    }

    @Test
    fun `takes the highest bitrate at or below the ceiling`() {
        val low = audioStream(bitrate = 48)
        val good = audioStream(bitrate = 160)
        val tooBig = audioStream(bitrate = 256)

        assertSame(good, selectAudioStream(listOf(low, good, tooBig)))
    }

    @Test
    fun `never picks a track above the ceiling when a smaller one exists`() {
        // The download cache never evicts, so an unnecessary 256 kbps track is storage the user
        // does not get back.
        val tooBig = audioStream(bitrate = 320)
        val fine = audioStream(bitrate = 96)

        assertSame(fine, selectAudioStream(listOf(tooBig, fine)))
    }

    @Test
    fun `falls back to the smallest track when everything exceeds the ceiling`() {
        // Refusing to play would be worse than playing something larger than we would like.
        val big = audioStream(bitrate = 320)
        val bigger = audioStream(bitrate = 480)

        assertSame(big, selectAudioStream(listOf(bigger, big)))
    }

    @Test
    fun `ignores dash and hls streams`() {
        // The sentinel URI carries no container hint, so Media3 builds a progressive media source
        // for it; a manifest handed to that fails at the first byte.
        val dash = audioStream(bitrate = 128, delivery = DeliveryMethod.DASH)
        val hls = audioStream(bitrate = 128, delivery = DeliveryMethod.HLS)
        val progressive = audioStream(bitrate = 64, delivery = DeliveryMethod.PROGRESSIVE_HTTP)

        assertSame(progressive, selectAudioStream(listOf(dash, hls, progressive)))
    }

    @Test
    fun `ignores streams that are manifests rather than urls`() {
        val manifest = audioStream(bitrate = 160, isUrl = false)
        val url = audioStream(bitrate = 64, isUrl = true)

        assertSame(url, selectAudioStream(listOf(manifest, url)))
    }

    @Test
    fun `ignores streams with a blank content url`() {
        val blank = audioStream(bitrate = 160, content = "")
        val real = audioStream(bitrate = 64)

        assertSame(real, selectAudioStream(listOf(blank, real)))
    }

    @Test
    fun `returns null when nothing is playable`() {
        assertNull(selectAudioStream(emptyList()))
        assertNull(selectAudioStream(listOf(audioStream(128, delivery = DeliveryMethod.DASH))))
    }

    @Test
    fun `never picks an auto-dubbed track over the original`() {
        // Found on the first real device run: an English talk downloaded as a German auto-dub,
        // because YouTube now lists dubbed tracks alongside the original and nothing ranked them.
        val germanDub = audioStream(bitrate = 128, trackType = AudioTrackType.DUBBED)
        val original = audioStream(bitrate = 64, trackType = AudioTrackType.ORIGINAL)

        assertSame(original, selectAudioStream(listOf(germanDub, original)))
    }

    @Test
    fun `rejects descriptive and secondary tracks`() {
        // Audio description narrates the picture, which is not the episode either.
        assertNull(
            selectAudioStream(listOf(audioStream(128, trackType = AudioTrackType.DESCRIPTIVE))),
        )
        assertNull(
            selectAudioStream(listOf(audioStream(128, trackType = AudioTrackType.SECONDARY))),
        )
    }

    @Test
    fun `keeps a track that declares no type at all`() {
        // The common case: one track, no track metadata. Filtering these out would break every
        // ordinary video.
        val plain = audioStream(bitrate = 128, trackType = null)

        assertSame(plain, selectAudioStream(listOf(plain)))
    }

    @Test
    fun `prefers the original even when the dub is larger and better formatted`() {
        val dub = audioStream(160, format = MediaFormat.M4A, trackType = AudioTrackType.DUBBED)
        val original =
            audioStream(48, format = MediaFormat.WEBMA_OPUS, trackType = AudioTrackType.ORIGINAL)

        assertSame(original, selectAudioStream(listOf(dub, original)))
    }

    @Test
    fun `applies the ceiling whatever unit the extractor reports`() {
        // The extractor reports YouTube audio in kbps, which made a bps-scaled ceiling inert: every
        // track passed it. Both spellings must now be bounded the same way.
        val kbps = selectAudioStream(listOf(audioStream(320), audioStream(128)))
        val bps = selectAudioStream(listOf(audioStream(320_000), audioStream(128_000)))

        assertEquals(128, kbps?.averageBitrate)
        assertEquals(128_000, bps?.averageBitrate)
    }

    // --- expiryOf ---------------------------------------------------------

    @Test
    fun `reads the expiry googlevideo puts in the url`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        val expiry = Instant.parse("2026-08-29T18:00:00Z")
        val url = "https://rr3.googlevideo.com/videoplayback?expire=${expiry.epochSecond}&itag=140"

        assertEquals(expiry, expiryOf(url, now))
    }

    @Test
    fun `assumes an hour when the url states no expiry`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")

        assertEquals(
            now.plus(Duration.ofHours(1)),
            expiryOf("https://cdn.example.com/audio.m4a", now),
        )
    }

    @Test
    fun `tolerates an unreadable expiry`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")

        assertEquals(
            now.plus(Duration.ofHours(1)),
            expiryOf("https://rr3.googlevideo.com/videoplayback?expire=soon", now),
        )
    }

    @Test
    fun `finds the expiry regardless of parameter position`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        val expiry = Instant.parse("2026-08-29T18:00:00Z")

        assertEquals(
            expiry,
            expiryOf(
                "https://rr3.googlevideo.com/videoplayback?itag=140&expire=${expiry.epochSecond}&x=1",
                now,
            ),
        )
    }

    @Test
    fun `does not mistake a similarly named parameter for the expiry`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")

        assertEquals(
            now.plus(Duration.ofHours(1)),
            expiryOf("https://rr3.googlevideo.com/videoplayback?noexpire=1234567890", now),
        )
    }

    private companion object {
        const val VIDEO_ID = "niTJ2221aS8"
        const val OTHER_VIDEO_ID = "aHsi-OHI_i8"

        /** Expires in 2033, so it stays fresh against [CLOCK] however these tests are ordered. */
        const val URL = "https://rr3.googlevideo.com/videoplayback?itag=140&expire=2000000000"

        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)
    }
}

package md.borisveriga.bpodcat.core.youtube

import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Tests the decisions inside the resolver that can be made without a network.
 *
 * Extraction itself is untestable offline and deliberately untested: a test that reached YouTube
 * would be a flaky dependency on their live infrastructure, and would fail for reasons that have
 * nothing to do with this code. What *is* worth pinning down is the stream choice — which decides
 * how much disk every downloaded video costs and whether it seeks properly — and the expiry
 * arithmetic, which decides whether a long download survives to the end.
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
}

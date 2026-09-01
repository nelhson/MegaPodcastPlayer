package md.borisveriga.bpodcat.core.media.youtube

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.time.Instant
import md.borisveriga.bpodcat.core.youtube.ResolvedYouTubeAudio
import md.borisveriga.bpodcat.core.youtube.YouTubeAudioResolver
import md.borisveriga.bpodcat.core.youtube.YouTubeAudioUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [YouTubeDataSpecResolver].
 *
 * The most important test here is [leaves an ordinary podcast url completely untouched]. This
 * resolver sits in the data source chain for *every* URL the app opens, so a mistake in its early
 * return would not break YouTube playback — it would break the entire existing podcast library, in
 * a way no YouTube-focused test would notice.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeDataSpecResolverTest {

    private val resolved = ResolvedYouTubeAudio(
        url = "https://rr3.googlevideo.com/videoplayback?itag=140&expire=1790000000",
        expiresAt = Instant.parse("2026-08-29T18:00:00Z"),
        durationMs = 4_500_000L,
        requestHeaders = mapOf("User-Agent" to "TestAgent/1.0"),
    )

    private val audioResolver: YouTubeAudioResolver = mockk {
        every { resolve(any()) } returns resolved
        every { invalidate(any()) } just Runs
    }

    private val resolver = YouTubeDataSpecResolver(audioResolver)

    @Test
    fun `swaps a sentinel for the resolved audio url`() {
        val spec = DataSpec.Builder().setUri("youtube://video/niTJ2221aS8".toUri()).build()

        val out = resolver.resolveDataSpec(spec)

        assertEquals(resolved.url, out.uri.toString())
        verify { audioResolver.resolve("niTJ2221aS8") }
    }

    @Test
    fun `passes the video id through with its case intact`() {
        // Video ids are case-sensitive. Anything that routed the id through Uri.getHost() would
        // lowercase it and quietly resolve a different video, or none.
        val spec = DataSpec.Builder().setUri("youtube://video/aHsi-OHI_i8".toUri()).build()

        resolver.resolveDataSpec(spec)

        verify { audioResolver.resolve("aHsi-OHI_i8") }
    }

    @Test
    fun `leaves an ordinary podcast url completely untouched`() {
        // The regression that protects every show already in the library.
        val spec = DataSpec.Builder()
            .setUri("https://cdn.example.com/episode-42.mp3".toUri())
            .setPosition(1024L)
            .setLength(2048L)
            .setKey("episode-42")
            .build()

        val out = resolver.resolveDataSpec(spec)

        assertSame(spec, out)
        verify(exactly = 0) { audioResolver.resolve(any()) }
    }

    @Test
    fun `preserves the byte range when resolving`() {
        // Media3 opens ranged specs when it resumes a partial download or seeks. Losing the range
        // would silently restart the transfer from zero every time.
        val spec = DataSpec.Builder()
            .setUri("youtube://video/niTJ2221aS8".toUri())
            .setPosition(5_000_000L)
            .setLength(1_000_000L)
            .build()

        val out = resolver.resolveDataSpec(spec)

        assertEquals(5_000_000L, out.position)
        assertEquals(1_000_000L, out.length)
    }

    @Test
    fun `preserves the cache key and flags`() {
        val spec = DataSpec.Builder()
            .setUri("youtube://video/niTJ2221aS8".toUri())
            .setKey("episode-7")
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build()

        val out = resolver.resolveDataSpec(spec)

        assertEquals("episode-7", out.key)
        assertEquals(DataSpec.FLAG_ALLOW_GZIP, out.flags)
    }

    @Test
    fun `adds the resolver's request headers`() {
        val spec = DataSpec.Builder().setUri("youtube://video/niTJ2221aS8".toUri()).build()

        val out = resolver.resolveDataSpec(spec)

        assertEquals("TestAgent/1.0", out.httpRequestHeaders["User-Agent"])
    }

    @Test
    fun `keeps headers the caller already set`() {
        val spec = DataSpec.Builder()
            .setUri("youtube://video/niTJ2221aS8".toUri())
            .setHttpRequestHeaders(mapOf("Range" to "bytes=0-100"))
            .build()

        val out = resolver.resolveDataSpec(spec)

        assertEquals("bytes=0-100", out.httpRequestHeaders["Range"])
        assertEquals("TestAgent/1.0", out.httpRequestHeaders["User-Agent"])
    }

    @Test
    fun `lets an unavailable video surface as an io exception`() {
        // Media3 turns an IOException into a retryable download failure and a PlaybackException the
        // UI already knows how to show; anything else would crash the loader thread.
        every { audioResolver.resolve(any()) } throws
            YouTubeAudioUnavailableException("niTJ2221aS8", "the video is private")
        val spec = DataSpec.Builder().setUri("youtube://video/niTJ2221aS8".toUri()).build()

        try {
            resolver.resolveDataSpec(spec)
            fail("Expected the resolver failure to propagate")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("private"))
        }
    }

    // --- invalidation -----------------------------------------------------

    @Test
    fun `invalidating a sentinel drops that video's resolution`() {
        val spec = DataSpec.Builder().setUri("youtube://video/aHsi-OHI_i8".toUri()).build()

        resolver.invalidate(spec)

        // Case-sensitive here for the same reason resolution is.
        verify { audioResolver.invalidate("aHsi-OHI_i8") }
    }

    @Test
    fun `invalidating a podcast url does nothing`() {
        // Failure invalidation is on the path for every download, not only for YouTube ones.
        val spec = DataSpec.Builder()
            .setUri("https://cdn.example.com/episode-42.mp3".toUri())
            .build()

        resolver.invalidate(spec)

        verify(exactly = 0) { audioResolver.invalidate(any()) }
    }
}

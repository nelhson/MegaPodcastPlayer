package md.borisveriga.bpodcat.core.media.youtube

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.io.InterruptedIOException
import md.borisveriga.bpodcat.core.youtube.YouTubeAudioResolver
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [YouTubeInvalidatingDataSource].
 *
 * The behaviour being pinned down is narrow and entirely about which failures count. Invalidating
 * too eagerly costs one extraction of about a second; invalidating too rarely costs the whole
 * download, because Media3 spends its retry ladder replaying a URL that cannot work. So the tests
 * that matter most are the two at the edges — a cancelled transfer, which must not invalidate, and
 * an ordinary podcast failure, which must not reach the resolver at all.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeInvalidatingDataSourceTest {

    private val audioResolver: YouTubeAudioResolver = mockk(relaxed = true)
    private val resolver = YouTubeDataSpecResolver(audioResolver)

    private fun source(upstream: DataSource) = YouTubeInvalidatingDataSource(upstream, resolver)

    private fun spec(uri: String) = DataSpec.Builder().setUri(uri.toUri()).build()

    @Test
    fun `an open that fails drops the resolution for that video`() {
        val source = source(FailingDataSource(failOnOpen = true))

        assertThrows(IOException::class.java) { source.open(spec(SENTINEL)) }

        verify(exactly = 1) { audioResolver.invalidate(VIDEO_ID) }
    }

    @Test
    fun `a read that fails part way drops the resolution too`() {
        // The common shape of the bug: the URL was fine when the transfer started and stopped being
        // fine before it finished.
        val source = source(FailingDataSource(failOnRead = true))
        source.open(spec(SENTINEL))

        assertThrows(IOException::class.java) { source.read(ByteArray(8), 0, 8) }

        verify(exactly = 1) { audioResolver.invalidate(VIDEO_ID) }
    }

    @Test
    fun `a cancelled transfer keeps the resolution`() {
        // A stopped download or a released player. The URL was never in question, and the resume
        // that follows should not pay for an extraction it does not need.
        val source = source(FailingDataSource(failOnRead = true, interrupted = true))
        source.open(spec(SENTINEL))

        assertThrows(InterruptedIOException::class.java) { source.read(ByteArray(8), 0, 8) }

        verify(exactly = 0) { audioResolver.invalidate(any()) }
    }

    @Test
    fun `a failing podcast download never reaches the resolver`() {
        // This source is on the failure path for the whole library, not just for YouTube.
        val source = source(FailingDataSource(failOnOpen = true))

        assertThrows(IOException::class.java) { source.open(spec(PODCAST_URL)) }

        verify(exactly = 0) { audioResolver.invalidate(any()) }
    }

    @Test
    fun `a transfer that works invalidates nothing`() {
        val source = source(FailingDataSource())

        source.open(spec(SENTINEL))
        source.read(ByteArray(8), 0, 8)
        source.close()

        verify(exactly = 0) { audioResolver.invalidate(any()) }
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val SENTINEL = "youtube://video/$VIDEO_ID"
        const val PODCAST_URL = "https://cdn.example.com/episode-42.mp3"
    }
}

/**
 * A data source that fails where it is told to.
 *
 * @property failOnOpen throw from `open`.
 * @property failOnRead throw from `read`.
 * @property interrupted throw [InterruptedIOException] rather than a plain [IOException].
 */
@UnstableApi
private class FailingDataSource(
    private val failOnOpen: Boolean = false,
    private val failOnRead: Boolean = false,
    private val interrupted: Boolean = false,
) : DataSource {

    private fun failure(): IOException =
        if (interrupted) InterruptedIOException("cancelled") else IOException("403")

    override fun open(dataSpec: DataSpec): Long {
        if (failOnOpen) throw failure()
        return 0L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (failOnRead) throw failure()
        return length
    }

    override fun close() = Unit

    override fun getUri() = null

    override fun addTransferListener(transferListener: TransferListener) = Unit
}

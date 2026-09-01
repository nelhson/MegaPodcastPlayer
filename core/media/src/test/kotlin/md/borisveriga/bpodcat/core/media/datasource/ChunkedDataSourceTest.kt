package md.borisveriga.bpodcat.core.media.datasource

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ChunkedDataSource].
 *
 * Two properties matter more than the rest and every test here serves one of them.
 *
 * **The bytes must be identical.** This sits under the download cache and under the player, so a
 * chunk boundary that drops, duplicates or reorders a single byte corrupts a downloaded episode in a
 * way nothing downstream would notice. Every chunking test therefore reads the whole content back
 * and compares it, rather than only asserting on the requests that were made.
 *
 * **A podcast must be untouched.** The factory that installs this is on the path for the entire
 * library, so the pass-through case is not a minor branch — it is what every existing subscription
 * depends on.
 *
 * [RecordingDataSource] deliberately mirrors the one behaviour of `OkHttpDataSource` this class is
 * built around: a bounded request reports the length that was *asked for*, never the size of the
 * document, so `Content-Range` is the only way to learn the latter.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedDataSourceTest {

    private val content = ByteArray(CONTENT_BYTES) { (it % Byte.MAX_VALUE).toByte() }

    private fun chunked(
        upstream: DataSource,
        chunkSizeBytes: Long = CHUNK_BYTES,
        shouldChunk: (DataSpec) -> Boolean = { true },
    ) = ChunkedDataSource(upstream, chunkSizeBytes, shouldChunk)

    private fun spec(position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder()
            .setUri(SENTINEL.toUri())
            .setPosition(position)
            .setLength(length)
            .build()

    // --- the pass-through case --------------------------------------------

    @Test
    fun `leaves a source it was told not to chunk completely alone`() {
        val upstream = RecordingDataSource(content)
        val source = chunked(upstream, shouldChunk = { false })

        val length = source.open(spec())

        assertEquals(CONTENT_BYTES.toLong(), length)
        assertArrayEquals(content, source.readFully())
        assertEquals(1, upstream.opens.size)
        // The whole point: still one unbounded request, exactly as before this class existed.
        assertEquals(C.LENGTH_UNSET.toLong(), upstream.opens.single().length)
    }

    // --- chunking ---------------------------------------------------------

    @Test
    fun `splits an unbounded read into bounded range requests`() {
        val upstream = RecordingDataSource(content)
        val source = chunked(upstream)

        val length = source.open(spec())

        // Learned from Content-Range, which is the only thing that carries it.
        assertEquals(CONTENT_BYTES.toLong(), length)
        assertArrayEquals(content, source.readFully())
        // The last chunk is clamped to what is left rather than overshooting the end.
        assertEquals(
            listOf(0L to 20L, 20L to 20L, 40L to 10L),
            upstream.opens.map { it.position to it.length },
        )
    }

    @Test
    fun `chunks a bounded read within the bounds it was given`() {
        val upstream = RecordingDataSource(content)
        val source = chunked(upstream)

        val length = source.open(spec(position = 10L, length = 25L))

        assertEquals(25L, length)
        assertArrayEquals(content.copyOfRange(10, 35), source.readFully())
        assertEquals(
            listOf(10L to 20L, 30L to 5L),
            upstream.opens.map { it.position to it.length },
        )
    }

    @Test
    fun `serves content shorter than one chunk in a single request`() {
        val upstream = RecordingDataSource(content)
        val source = chunked(upstream, chunkSizeBytes = 500L)

        val length = source.open(spec())

        assertEquals(CONTENT_BYTES.toLong(), length)
        assertArrayEquals(content, source.readFully())
        assertEquals(1, upstream.opens.size)
    }

    @Test
    fun `never returns more bytes than remain in the open chunk`() {
        val upstream = RecordingDataSource(content)
        val source = chunked(upstream)
        source.open(spec())

        // Asked for far more than one chunk holds; a read may not span a boundary.
        val read = source.read(ByteArray(CONTENT_BYTES), 0, CONTENT_BYTES)

        assertEquals(CHUNK_BYTES.toInt(), read)
    }

    @Test
    fun `reads nothing when asked for nothing`() {
        val upstream = RecordingDataSource(content)
        val source = chunked(upstream)
        source.open(spec())

        assertEquals(0, source.read(ByteArray(1), 0, 0))
    }

    @Test
    fun `closes each chunk before opening the next, and the last one on close`() {
        val upstream = RecordingDataSource(content)
        val source = chunked(upstream)
        source.open(spec())
        source.readFully()

        // Two boundaries between three chunks.
        assertEquals(2, upstream.closes)

        source.close()

        assertEquals(3, upstream.closes)
    }

    // --- degrading gracefully ---------------------------------------------

    @Test
    fun `still yields the whole content when the server reports no content range`() {
        // What a server that ignored the Range header looks like from here. Media3's HTTP sources
        // skip forward to the requested position themselves, so the transfer is still correct — the
        // only thing lost is knowing the length up front.
        val upstream = RecordingDataSource(content, reportsContentRange = false)
        val source = chunked(upstream)

        val length = source.open(spec())

        assertEquals(C.LENGTH_UNSET.toLong(), length)
        assertArrayEquals(content, source.readFully())
    }

    @Test
    fun `fails rather than silently truncating when a chunk stops early`() {
        // The dangerous case. CacheWriter treats end-of-input on its last block as proof the content
        // ended there and rewrites its end position to match, so reporting the end here would mark a
        // half-downloaded episode complete. Failing lets Media3 retry and resume instead.
        val upstream = RecordingDataSource(content, diesAt = 25)
        val source = chunked(upstream)
        source.open(spec())

        assertThrows(EOFException::class.java) { source.readFully() }
    }

    private companion object {
        const val CONTENT_BYTES = 50
        const val CHUNK_BYTES = 20L
        const val SENTINEL = "youtube://video/dQw4w9WgXcQ"
    }
}

/** Reads until the source reports the end, and returns everything it produced. */
@UnstableApi
private fun DataSource.readFully(): ByteArray {
    val sink = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (true) {
        val read = read(buffer, 0, buffer.size)
        if (read == C.RESULT_END_OF_INPUT) return sink.toByteArray()
        sink.write(buffer, 0, read)
    }
}

/** Small enough that several reads fall inside one chunk and one straddles a boundary. */
private const val READ_BUFFER_BYTES = 16

/**
 * A data source that serves [content] over HTTP's range semantics, and records how it was asked.
 *
 * Faithful to `OkHttpDataSource` in the two places that decide whether [ChunkedDataSource] is
 * correct:
 *  - a bounded `open` returns `dataSpec.length` verbatim, whatever the server actually sent, so the
 *    return value can never reveal the size of the document;
 *  - a server clamps a range that overshoots to the end of the document, so the last chunk of an
 *    over-long request comes back short.
 *
 * @property content the bytes being served.
 * @property reportsContentRange false to imitate a server that ignores `Range` and answers `200`.
 * @property diesAt an absolute offset at which the transfer stops dead, or -1 for a healthy one.
 */
@UnstableApi
private class RecordingDataSource(
    private val content: ByteArray,
    private val reportsContentRange: Boolean = true,
    private val diesAt: Int = -1,
) : DataSource {

    val opens = mutableListOf<DataSpec>()
    var closes = 0
        private set

    private var position = 0
    private var chunkRemaining = 0
    private var headers: Map<String, List<String>> = emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        opens += dataSpec
        position = dataSpec.position.toInt()

        val bounded = dataSpec.length != C.LENGTH_UNSET.toLong()
        val requested = if (bounded) dataSpec.length.toInt() else content.size - position
        val served = min(requested, content.size - position)
        chunkRemaining = served

        headers = if (reportsContentRange && bounded && served > 0) {
            val last = position + served - 1
            mapOf("Content-Range" to listOf("bytes $position-$last/${content.size}"))
        } else {
            emptyMap()
        }

        return if (bounded) dataSpec.length else served.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (chunkRemaining == 0 || (diesAt >= 0 && position >= diesAt)) {
            return C.RESULT_END_OF_INPUT
        }
        var toRead = min(length, chunkRemaining)
        if (diesAt >= 0) toRead = min(toRead, diesAt - position)

        content.copyInto(buffer, offset, position, position + toRead)
        position += toRead
        chunkRemaining -= toRead
        return toRead
    }

    override fun close() {
        closes++
    }

    override fun getUri() = opens.lastOrNull()?.uri

    override fun getResponseHeaders() = headers

    override fun addTransferListener(transferListener: TransferListener) = Unit
}

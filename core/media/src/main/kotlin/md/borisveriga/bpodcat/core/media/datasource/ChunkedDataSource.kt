package md.borisveriga.bpodcat.core.media.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import kotlin.math.min

/**
 * Serves one long read as a series of bounded range requests.
 *
 * ## Why this exists
 *
 * Media3 downloads a progressive file with a single unbounded `GET`. `ProgressiveDownloader` builds
 * its `DataSpec` with no length, `CacheWriter` therefore opens the first block unbounded, and
 * `HttpUtil.buildRangeRequestHeader(0, LENGTH_UNSET)` returns `null` — so the request that fetches a
 * whole episode carries no `Range` header at all.
 *
 * An ordinary podcast CDN answers that at line speed. `googlevideo.com` does not: an un-ranged
 * progressive response is rate-limited to roughly playback speed, which is the entire reason a
 * seventy-megabyte YouTube episode took an hour to download while a comparable podcast took a
 * minute. Ranged requests are served at full speed, which is why every downloader that works
 * against YouTube — `yt-dlp` included — fetches in chunks.
 *
 * So this sits in the chain and turns one unbounded open into `ceil(total / chunkSizeBytes)`
 * bounded ones, each carrying a real `Range` header, and presents the result to its caller as a
 * single continuous stream. Callers above cannot tell the difference; the bytes, their order and
 * the reported length are identical.
 *
 * ## Placement
 *
 * Above `ResolvingDataSource` and below the cache. Above the resolver because [shouldChunk] is
 * given the *original* spec, so the cheap `youtube://` test still works — below the resolver it
 * would see an expiring `googlevideo.com` URL and have to sniff the host instead. Below the cache
 * because chunk boundaries are a transport detail that must never reach a cache key.
 *
 * A useful side effect of the placement: each chunk re-enters the resolver, so a download long
 * enough to outlive its resolved URL picks up a fresh one at the next chunk boundary rather than
 * dying on a 403 halfway through.
 *
 * ## What it does not do
 *
 * Nothing at all to sources [shouldChunk] rejects — they are handed to [upstream] untouched, one
 * open, one request, exactly as before. That is deliberate: podcast enclosures are not throttled,
 * and splitting them would buy nothing while adding a round trip per chunk and a dependency on
 * `Range` support that a long tail of self-hosted feeds might not have.
 *
 * @property upstream the data source that performs the actual requests.
 * @property chunkSizeBytes how many bytes each range request asks for.
 * @property shouldChunk decides, from the spec as it was opened, whether to chunk at all.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSizeBytes: Long,
    private val shouldChunk: (DataSpec) -> Boolean,
) : DataSource {

    /** The spec this source was opened with, kept to build each chunk's spec from. */
    private var openedSpec: DataSpec? = null

    /** True when [upstream] is being used unchanged — see the class doc. */
    private var passThrough = false

    /** Whether [upstream] currently has something open that [close] must close. */
    private var upstreamOpen = false

    /** Absolute position of the next byte to request. */
    private var nextPosition = 0L

    /**
     * Bytes still to be read across the whole request, or [C.LENGTH_UNSET] while unknown.
     *
     * Unknown only until the first chunk's `Content-Range` is read; see [openNextChunk].
     */
    private var totalBytesRemaining = LENGTH_UNSET

    /** Bytes still to be read from the chunk currently open. */
    private var chunkBytesRemaining = 0L

    /**
     * Opens the source, and with it the first chunk.
     *
     * @param dataSpec what to read. May be bounded or unbounded, at any position.
     * @return the total number of bytes that can be read, or [C.LENGTH_UNSET] if that could not be
     *   established — which, when chunking, means the first response carried no usable
     *   `Content-Range`.
     */
    override fun open(dataSpec: DataSpec): Long {
        openedSpec = dataSpec
        passThrough = !shouldChunk(dataSpec)

        if (passThrough) {
            val length = upstream.open(dataSpec)
            upstreamOpen = true
            return length
        }

        nextPosition = dataSpec.position
        totalBytesRemaining = dataSpec.length
        openNextChunk()
        return totalBytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = if (passThrough) {
        upstream.read(buffer, offset, length)
    } else {
        readChunked(buffer, offset, length)
    }

    /**
     * Reads across chunk boundaries, opening the next chunk whenever the current one runs out.
     *
     * A read is never allowed to span two chunks: it is clamped to what remains of the open one and
     * the caller comes back for the rest. `CacheWriter` and `ProgressiveMediaPeriod` both read in a
     * loop, so a short read costs an extra iteration and nothing else.
     */
    private fun readChunked(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!ensureChunkWithBytes()) return C.RESULT_END_OF_INPUT

        val toRead = min(length.toLong(), chunkBytesRemaining).toInt()
        val bytesRead = upstream.read(buffer, offset, toRead)
        if (bytesRead == C.RESULT_END_OF_INPUT) return onChunkEndedEarly()

        chunkBytesRemaining -= bytesRead
        nextPosition += bytesRead
        if (totalBytesRemaining != LENGTH_UNSET) {
            totalBytesRemaining -= bytesRead
        }
        return bytesRead
    }

    /**
     * Makes sure a chunk with bytes left in it is open.
     *
     * @return false when the whole request has been read and there is no next chunk.
     */
    private fun ensureChunkWithBytes(): Boolean {
        if (totalBytesRemaining == 0L) return false
        if (chunkBytesRemaining > 0L) return true

        closeUpstream()
        openNextChunk()
        return chunkBytesRemaining > 0L
    }

    /**
     * Opens the chunk beginning at [nextPosition].
     *
     * The request is always bounded, which is the whole point: a bounded spec is what makes
     * `HttpUtil.buildRangeRequestHeader` emit a `Range` header. It is clamped to what is left of the
     * request so that, once the total is known, no chunk ever asks for a byte past the end — a range
     * that overshoots would come back short and be indistinguishable from a truncated transfer.
     *
     * The first chunk is where the total length is discovered. `OkHttpDataSource.open` returns
     * `dataSpec.length` verbatim for a bounded request, so it reports the size of the *chunk* and
     * never the size of the content; the only thing that carries the latter is the `Content-Range`
     * header on the `206`. Getting it is not cosmetic — `CacheWriter` sets its end position from
     * what [open] returns, so without it every YouTube download would report unknown progress from
     * start to finish.
     *
     * Never called with nothing left to request: [ensureChunkWithBytes] checks that first, and
     * `DataSpec` refuses to be built with a length of zero, so the spec [open] starts from always
     * asks for at least one byte.
     */
    private fun openNextChunk() {
        val requestLength = if (totalBytesRemaining == LENGTH_UNSET) {
            chunkSizeBytes
        } else {
            min(chunkSizeBytes, totalBytesRemaining)
        }
        val chunkSpec = checkNotNull(openedSpec).buildUpon()
            .setPosition(nextPosition)
            .setLength(requestLength)
            .build()

        upstream.open(chunkSpec)
        upstreamOpen = true
        chunkBytesRemaining = requestLength

        if (totalBytesRemaining == LENGTH_UNSET) {
            adoptTotalLengthFromContentRange()
        }
    }

    /**
     * Learns the content's real length from the chunk that was just opened.
     *
     * Leaves [totalBytesRemaining] unknown when the response carried no parsable `Content-Range`,
     * which is what a server that ignored the `Range` header looks like. That is survivable rather
     * than fatal: the transfer still works — Media3's HTTP sources skip forward to the requested
     * position when they get a `200` — it merely reports unknown progress and falls back to treating
     * a short chunk as the end of the content.
     */
    private fun adoptTotalLengthFromContentRange() {
        val documentSize = HttpUtil.getDocumentSize(contentRangeHeader())
        if (documentSize == LENGTH_UNSET) return

        totalBytesRemaining = (documentSize - nextPosition).coerceAtLeast(0L)
        chunkBytesRemaining = min(chunkBytesRemaining, totalBytesRemaining)
    }

    /**
     * The `Content-Range` of the response currently open, if it has one.
     *
     * Matched case-insensitively rather than by exact key. OkHttp's `toMultimap` already returns a
     * case-insensitive map, but this source is written against the `DataSource` interface and not
     * against one implementation of it.
     */
    private fun contentRangeHeader(): String? = upstream.responseHeaders.entries
        .firstOrNull { (name, _) -> name.equals(CONTENT_RANGE_HEADER, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

    /**
     * Handles a chunk that stopped delivering before it had produced the bytes it was asked for.
     *
     * The two cases are genuinely different and must not be conflated.
     *
     * When the total is known, every chunk was clamped to fit inside it, so the transfer was cut
     * short and this throws. That matters more than it looks: `CacheWriter` treats end-of-input on
     * its last block as proof that the content really ended there and rewrites its end position to
     * match, so returning [C.RESULT_END_OF_INPUT] here would mark a truncated download complete.
     * Failing instead lets Media3 retry and resume from what was cached.
     *
     * When the total is unknown, there is nothing to compare against and a short chunk is far more
     * likely to be the end of the content than a truncation, so it is reported as such.
     */
    private fun onChunkEndedEarly(): Int {
        if (totalBytesRemaining == LENGTH_UNSET) {
            totalBytesRemaining = 0L
            chunkBytesRemaining = 0L
            return C.RESULT_END_OF_INPUT
        }
        throw EOFException(
            "Chunk at $nextPosition ended $chunkBytesRemaining bytes short of what was requested",
        )
    }

    /**
     * Closes whatever [upstream] has open, if anything.
     *
     * Called both between chunks and from [close]; every Media3 data source tolerates a redundant
     * close, and the flag is what keeps a chunk boundary from looking like a closed source.
     */
    private fun closeUpstream() {
        if (!upstreamOpen) return
        upstreamOpen = false
        upstream.close()
    }

    override fun close() {
        try {
            closeUpstream()
        } finally {
            openedSpec = null
            passThrough = false
            nextPosition = 0L
            totalBytesRemaining = LENGTH_UNSET
            chunkBytesRemaining = 0L
        }
    }

    /**
     * The URI being read from.
     *
     * Falls back to the opened spec's URI for the instant between closing one chunk and opening the
     * next, when [upstream] legitimately has nothing to report. Nothing observes that gap today —
     * it only ever exists inside [readChunked] — but a null URI from an open source would be a
     * confusing thing to leave available.
     */
    override fun getUri(): Uri? = if (upstreamOpen) upstream.uri else openedSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    /**
     * Builds a [ChunkedDataSource] over each source [upstreamFactory] creates.
     *
     * @property upstreamFactory the chain this wraps.
     * @property chunkSizeBytes bytes per range request.
     * @property shouldChunk which specs to chunk, tested against the spec as it was opened.
     */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkSizeBytes: Long,
        private val shouldChunk: (DataSpec) -> Boolean,
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource = ChunkedDataSource(
            upstream = upstreamFactory.createDataSource(),
            chunkSizeBytes = chunkSizeBytes,
            shouldChunk = shouldChunk,
        )
    }

    private companion object {
        /** The header a `206` states the full document size in. */
        const val CONTENT_RANGE_HEADER = "Content-Range"

        /** [C.LENGTH_UNSET] as the `Long` every length in this class is measured in. */
        val LENGTH_UNSET: Long = C.LENGTH_UNSET.toLong()
    }
}

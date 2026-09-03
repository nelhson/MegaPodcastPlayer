package md.borisveriga.megapodcastplayer.core.media.youtube

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Watches a resolved YouTube request fail, and throws away the resolution that produced it.
 *
 * ## Why a data source rather than something simpler
 *
 * [YouTubeDataSpecResolver] resolves a sentinel into a real `googlevideo.com` URL and caches the
 * result until its stated expiry. Nothing tells it when that URL stops working early — and URLs do
 * stop working early, because they are bound to the IP that asked for them, so every one the app
 * holds dies the moment the phone moves between Wi-Fi and mobile data while every stated expiry is
 * still hours away.
 *
 * What that costs without this class is worth being precise about. Media3's `DownloadThread` retries
 * a failed download up to `minRetryCount` times, re-opening the data source each time — and every
 * one of those opens goes back through the resolver, which cheerfully returns the same dead URL from
 * its cache. So the retry ladder is spent replaying a request that cannot succeed, and the download
 * ends in `STATE_FAILED` over something one re-extraction would have fixed. Worse, that same loop is
 * *unbounded* when each attempt manages a few bytes first: `DownloadThread` resets its error count
 * whenever the byte position has moved since the last failure, so a URL that dies part way through
 * can be retried for as long as the service lives.
 *
 * A `Resolver` cannot notice any of this: it is called on the way *into* an open and never hears how
 * the open went. A data source is the nearest thing in the chain that does.
 *
 * ## Placement
 *
 * Directly above [androidx.media3.datasource.ResolvingDataSource], so it wraps exactly the open that
 * a single resolution is used for and sees that open's failure unwrapped. Above it in turn sits
 * [md.borisveriga.megapodcastplayer.core.media.datasource.ChunkedDataSource], which means each chunk of a
 * download gets this treatment individually.
 *
 * @property upstream the resolving chain this wraps.
 * @property resolver the resolver whose cache is being kept honest.
 */
@UnstableApi
class YouTubeInvalidatingDataSource(
    private val upstream: DataSource,
    private val resolver: YouTubeDataSpecResolver,
) : DataSource {

    /** The spec currently open, which is the one whose resolution would have to be discarded. */
    private var openedSpec: DataSpec? = null

    override fun open(dataSpec: DataSpec): Long {
        openedSpec = dataSpec
        return invalidatingOnFailure { upstream.open(dataSpec) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        invalidatingOnFailure { upstream.read(buffer, offset, length) }

    override fun close() {
        try {
            upstream.close()
        } finally {
            openedSpec = null
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    /**
     * Runs [block], discarding the current resolution if it fails.
     *
     * Deliberately indiscriminate about *which* [IOException] it reacts to. The failure that matters
     * is a `403` on a dead URL, but a read that stops mid-transfer and a connection reset look much
     * the same from here, and the penalty for over-reacting is one extraction of roughly a second on
     * a path that is already retrying with a backoff. Guessing wrong in the other direction costs
     * the whole download.
     *
     * [InterruptedIOException] is the exception: that is a cancelled download or a stopped player,
     * where the URL was never in question and the next thing to happen is a resume that would rather
     * not pay for an extraction it does not need.
     */
    private inline fun <T> invalidatingOnFailure(block: () -> T): T =
        try {
            block()
        } catch (interrupted: InterruptedIOException) {
            throw interrupted
        } catch (failure: IOException) {
            openedSpec?.let(resolver::invalidate)
            throw failure
        }

    /**
     * Builds a [YouTubeInvalidatingDataSource] over each source [upstreamFactory] creates.
     *
     * @property upstreamFactory the resolving chain this wraps.
     * @property resolver the resolver whose cache is being kept honest.
     */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val resolver: YouTubeDataSpecResolver,
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource = YouTubeInvalidatingDataSource(
            upstream = upstreamFactory.createDataSource(),
            resolver = resolver,
        )
    }
}

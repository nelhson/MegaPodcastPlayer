package md.borisveriga.bpodcat.core.media.download

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import md.borisveriga.bpodcat.core.common.di.BPodcatDispatcher
import md.borisveriga.bpodcat.core.common.di.Dispatcher
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.media.di.DownloadCache

/**
 * Reads a downloaded episode's bytes back out of the download cache.
 *
 * The cache is not a directory of files: Media3 stores content in spans of its own naming, indexed
 * by cache key, and the only supported way back to a contiguous stream is to read it as a data
 * source. That is the whole reason this exists rather than the caller opening a [java.io.File].
 *
 * Deliberately **cache-only**. There is no upstream factory, so a read past what is on disk fails
 * instead of quietly fetching the remainder over mobile data — which for the one caller, sending an
 * episode to a watch, would be a download the user never asked for, paid for twice.
 *
 * It lives here rather than in the caller because this module owns the cache and its quirks: the key
 * is the audio URL (see [EpisodeDownloader.download] on why no custom key), and the length is
 * metadata the cache keeps rather than the size the feed advertised.
 *
 * @property cache the download cache, holding whatever the user has taken offline.
 * @property ioDispatcher where the reads happen; a full episode is tens of megabytes.
 */
@OptIn(UnstableApi::class)
@Singleton
class DownloadedAudio @Inject constructor(
    @DownloadCache private val cache: Cache,
    @Dispatcher(BPodcatDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * How many bytes of an episode the cache holds a length for.
     *
     * This is the cache's own record of the content length, not the `length` the feed advertised —
     * feeds are routinely wrong about it, and this figure is used to show a transfer's progress,
     * which a wrong denominator makes worse than useless.
     *
     * @param audioUrl the episode's audio URL, which is also its cache key.
     * @return the length in bytes, or zero when the cache does not know it.
     */
    suspend fun lengthOf(audioUrl: String): Long = withContext(ioDispatcher) {
        suspendRunCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(audioUrl))
                .takeIf { it != C.LENGTH_UNSET.toLong() }
                ?: 0L
        }.getOrElse { 0L }
    }

    /**
     * Copies an episode's audio into [sink].
     *
     * Cancellation is honoured between blocks rather than only at the end: the caller is writing to
     * a Bluetooth channel, and a transfer the user walked away from must stop taking their radio
     * with it.
     *
     * @param audioUrl the episode's audio URL, which is also its cache key.
     * @param sink where to write; closing it is the caller's business, since the caller owns it.
     * @param onProgress called with the running total as the copy proceeds, at most once per block.
     * @return the number of bytes written, or null if the episode was not fully in the cache or the
     *   copy failed part way — in which case [sink] holds a partial episode and the caller must
     *   discard it.
     */
    suspend fun copyTo(
        audioUrl: String,
        sink: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Long? = withContext(ioDispatcher) {
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            // No upstream: a gap in the cache must fail the copy, not fetch the rest.
            .setUpstreamDataSourceFactory(null)
            .createDataSource()

        suspendRunCatching {
            dataSource.open(DataSpec(audioUrl.toUri()))
            try {
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    sink.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
                sink.flush()
                total
            } finally {
                dataSource.close()
            }
        }.getOrNull()
    }

    private companion object {
        /**
         * How much is moved per read.
         *
         * Sized for the far end rather than for the disk: these bytes go straight onto a Bluetooth
         * channel, whose throughput is measured in hundreds of kilobytes per second, so a larger
         * buffer only means a longer wait between progress reports.
         */
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

package md.borisveriga.megapodcastplayer.core.media.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.media.di.DownloadCache

/**
 * Reads a downloaded episode's audio back out of the download cache, as one continuous stream.
 *
 * A download is not a file: Media3 keeps it as spans in [DownloadCache], indexed by the episode's
 * audio URL. Anything that wants the bytes as a whole — exporting them to a folder the user can see
 * — has to go back through Media3 to get them. This is that way back.
 */
interface DownloadedAudioReader {

    /**
     * Whether every byte of an episode's audio is in the cache.
     *
     * Asked before [open], because the database's `COMPLETED` is a mirror of Media3's index and a
     * mirror can lag. An episode that is only partly cached would otherwise be exported cut short,
     * and a truncated file looks exactly like a whole one until the moment it stops playing.
     *
     * @param audioUrl the episode's stored audio URL, which is also its cache key.
     * @return true only when the length is known and all of it is cached.
     */
    fun isFullyDownloaded(audioUrl: String): Boolean

    /**
     * How many bytes an episode's audio has, as the download recorded it.
     *
     * What a copy of it must be as long as: an export uses it to tell a finished file in the folder
     * from one an interrupted run left half-written.
     *
     * @param audioUrl the episode's stored audio URL, which is also its cache key.
     * @return the length, or null when the download never learned it.
     */
    fun contentLength(audioUrl: String): Long?

    /**
     * Opens an episode's cached audio.
     *
     * Blocking: call it, and read the stream, off the main thread. Never reaches the network — a
     * span missing from the cache is an [java.io.IOException] from the stream, not a download.
     *
     * @param audioUrl the episode's stored audio URL, which is also its cache key.
     * @return the audio from its first byte; the caller closes it.
     */
    fun open(audioUrl: String): InputStream
}

/**
 * [DownloadedAudioReader] over the one [DownloadCache].
 *
 * The cache is injected rather than opened: `SimpleCache` locks its directory, so a second instance
 * over the same folder throws.
 *
 * @property cache the download cache.
 */
@Singleton
@OptIn(UnstableApi::class)
class CacheDownloadedAudioReader @Inject constructor(
    @param:DownloadCache private val cache: Cache,
) : DownloadedAudioReader {

    override fun isFullyDownloaded(audioUrl: String): Boolean {
        val length = contentLength(audioUrl) ?: return false
        return cache.isCached(audioUrl, 0, length)
    }

    override fun contentLength(audioUrl: String): Long? {
        // The key is the URL exactly as stored — the `youtube://video/<id>` sentinel for a video —
        // because that is what the download was indexed under. See `EpisodeDownloader`.
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(audioUrl))
        return length.takeIf { it != C.LENGTH_UNSET.toLong() }
    }

    override fun open(audioUrl: String): InputStream {
        // No upstream: a missing span fails instead of being fetched. An export is a copy of what
        // is on the device, never a quiet re-download of what is not.
        val dataSource = CacheDataSource(
            cache,
            /* upstreamDataSource = */ null,
            CacheDataSource.FLAG_BLOCK_ON_CACHE,
        )
        return DataSourceInputStream(dataSource, DataSpec.Builder().setUri(audioUrl).build())
    }
}

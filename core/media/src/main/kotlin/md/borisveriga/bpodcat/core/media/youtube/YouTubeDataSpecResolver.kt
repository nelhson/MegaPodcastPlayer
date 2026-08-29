package md.borisveriga.bpodcat.core.media.youtube

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.bpodcat.core.model.youTubeVideoIdOrNull
import md.borisveriga.bpodcat.core.youtube.YouTubeAudioResolver

/**
 * Swaps the durable `youtube://video/<id>` sentinel for a real, short-lived audio URL at the moment
 * the bytes are actually requested.
 *
 * Placement is the whole design. This sits *below* the cache in both data source chains, so the
 * cache entries and the download index are keyed by the sentinel and never by the resolved URL.
 * That is what lets a downloaded episode keep playing from disk long after the URL it was fetched
 * from has expired — and, in the other direction, what stops one video accumulating a new cache
 * entry every time it is played.
 *
 * @property resolver performs the actual extraction.
 */
@UnstableApi
@Singleton
class YouTubeDataSpecResolver @Inject constructor(
    private val resolver: YouTubeAudioResolver,
) : ResolvingDataSource.Resolver {

    /**
     * Resolves a data spec immediately before it is opened.
     *
     * @param dataSpec the spec Media3 is about to open. May be for any URL at all.
     * @return the spec to actually open — unchanged for everything that is not a YouTube sentinel.
     * @throws java.io.IOException when the video has no playable audio, or the network fails.
     */
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        // This resolver is on the path for *every* URL, including every ordinary podcast MP3. The
        // early return is what keeps the existing library working, so it comes first and does the
        // cheapest possible test.
        val videoId = youTubeVideoIdOrNull(dataSpec.uri.toString()) ?: return dataSpec

        val audio = resolver.resolve(videoId)

        // buildUpon() carries position, length, flags and key across, which matters: Media3 opens
        // ranged specs when it resumes a partial download or seeks, and dropping the range would
        // silently restart the transfer from zero.
        return dataSpec.buildUpon()
            .setUri(audio.url.toUri())
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + audio.requestHeaders)
            .build()
    }

    // resolveReportedUri is deliberately not overridden. The default reports the URI unchanged,
    // which is what upstream components should see: the sentinel is the stable identity of this
    // media, and the resolved URL is an implementation detail that changes on every open.
}

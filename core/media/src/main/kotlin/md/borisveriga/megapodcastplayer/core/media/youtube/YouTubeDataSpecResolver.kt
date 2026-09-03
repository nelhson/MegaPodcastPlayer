package md.borisveriga.megapodcastplayer.core.media.youtube

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoIdOrNull
import md.borisveriga.megapodcastplayer.core.youtube.YouTubeAudioResolver

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

    /**
     * Throws away the resolution [resolveDataSpec] handed out for [dataSpec], if it was a YouTube
     * one, so the next open extracts a fresh URL.
     *
     * Called by [YouTubeInvalidatingDataSource] when a request built from a resolved spec fails. It
     * takes the *original* spec rather than a video id so that the one place that knows how to read
     * a sentinel stays the one place that reads a sentinel — the caller is a general-purpose data
     * source and has no business parsing this app's URIs.
     *
     * Does nothing for an ordinary podcast URL, which is the common case: this is on the failure
     * path for every download in the library, not just for YouTube ones.
     *
     * @param dataSpec the spec as it was opened, before resolution.
     */
    fun invalidate(dataSpec: DataSpec) {
        val videoId = youTubeVideoIdOrNull(dataSpec.uri.toString()) ?: return
        resolver.invalidate(videoId)
    }

    // resolveReportedUri is deliberately not overridden. The default reports the URI unchanged,
    // which is what upstream components should see: the sentinel is the stable identity of this
    // media, and the resolved URL is an implementation detail that changes on every open.
}

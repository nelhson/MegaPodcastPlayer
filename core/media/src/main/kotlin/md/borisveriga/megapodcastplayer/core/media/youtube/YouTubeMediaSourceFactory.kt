package md.borisveriga.megapodcastplayer.core.media.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoOnlyRefOrNull

/**
 * Builds the media source for a YouTube episode shown as video, and leaves everything else to
 * Media3's default.
 *
 * YouTube publishes nothing sharper than 360p with sound and picture in one file, so a video item
 * is two progressive streams played together: the video-only rendition its sentinel names, and the
 * audio the episode already plays as. [MergingMediaSource] plays them as one, and both halves go
 * through the ordinary data source chain — cache, chunking, invalidation, resolution — because the
 * chain reads sentinels, and both halves are sentinels. That is what lets the audio half of a
 * downloaded episode come off disk while its picture streams.
 *
 * Every item that is not a video sentinel, which is every episode ever stored, takes the delegate's
 * path untouched.
 *
 * @param delegate what builds every ordinary source.
 * @param dataSourceFactory the player's data source chain, which both halves of a video read
 *   through.
 */
@UnstableApi
class YouTubeMediaSourceFactory(
    private val delegate: DefaultMediaSourceFactory,
    dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory {

    /** Builds each half of a video; the delegate would infer a type from the URI and get it wrong. */
    private val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val ref = mediaItem.localConfiguration?.uri?.toString()?.let(::youTubeVideoOnlyRefOrNull)
            ?: return delegate.createMediaSource(mediaItem)
        val audioItem = mediaItem.buildUpon().setUri(youTubeAudioSentinel(ref.videoId)).build()
        return MergingMediaSource(
            // The two files start at slightly different offsets; line them up rather than let the
            // picture lead the sound by a frame.
            /* adjustPeriodTimeOffsets = */ true,
            // And end together, so the longer half cannot run on alone past the other.
            /* clipDurations = */ true,
            // Picture first: a merged source reports its first child's item as its own, and the
            // video sentinel is what tells the rest of the app the picture is showing.
            progressive.createMediaSource(mediaItem),
            progressive.createMediaSource(audioItem),
        )
    }
}

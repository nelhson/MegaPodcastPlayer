package md.borisveriga.megapodcastplayer.core.media

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.youtube.YouTubeVideoResolver

/**
 * Answers the one question the video screen asks the extractor directly: which renditions does
 * this video come in?
 *
 * Exists so that `:core:youtube` can stay an implementation detail of this module. The resolver's
 * call is blocking by design (see [YouTubeVideoResolver.availableQualities]); this is the
 * coroutine-shaped door to it, on the IO dispatcher.
 *
 * @property resolver the extractor-backed resolver, which caches, so asking here right after the
 *   player started the same video costs nothing.
 * @property ioDispatcher where the blocking call runs.
 */
@Singleton
class VideoQualitySource @Inject constructor(
    private val resolver: YouTubeVideoResolver,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * The rendition heights [videoId] can be shown at, lowest first; empty when it has no picture.
     *
     * @param videoId YouTube's video id.
     * @throws java.io.IOException when the video cannot be extracted; the caller words it.
     */
    suspend fun qualitiesOf(videoId: String): List<VideoQuality> = withContext(ioDispatcher) {
        resolver.availableQualities(videoId)
    }
}

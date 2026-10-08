package md.borisveriga.megapodcastplayer.core.network.rss

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.model.youTubePlaylistFeedUrl

/**
 * Downloads and parses YouTube's published feed for one playlist.
 *
 * The sibling of [FeedRemoteDataSource] for the one URL shape it no longer handles. It is simpler
 * on purpose: the endpoint sends neither `ETag` nor `Last-Modified`, so there are no validators to
 * carry and no `304` to answer with — every fetch re-reads the (at most fifteen) entries, and the
 * repository already treats a YouTube show as never "unchanged".
 *
 * The stored feed URL is, for once, used as an address. Everywhere else in the app it is an
 * identity — hashed into the podcast id and meaning nothing to the extractor — and this is the one
 * place that actually asks YouTube for it.
 *
 * @property api Retrofit client for arbitrary feed URLs.
 * @property parser the streaming Atom parser.
 * @property ioDispatcher dispatcher for the blocking parse; the HTTP call itself already suspends.
 */
@Singleton
class YouTubeAtomFeedDataSource @Inject constructor(
    private val api: FeedApi,
    private val parser: YouTubeAtomParser,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Fetches and parses the feed of [playlistId].
     *
     * @param playlistId the canonical playlist id, e.g. `PLAA9qRhhXQ2c`.
     * @return the playlist as the feed publishes it: its newest entries, in the feed's order.
     * @throws RssParseException if the body is not a playlist feed — a private or deleted playlist
     *   answers with an HTML page and a 200.
     * @throws IOException on network failure or a non-2xx status.
     */
    suspend fun fetch(playlistId: String): FeedChannel = withContext(ioDispatcher) {
        val feedUrl = youTubePlaylistFeedUrl(playlistId)
        val response = api.getFeed(url = feedUrl, etag = null, lastModified = null)

        if (!response.isSuccessful) {
            throw IOException("Playlist feed request failed with HTTP ${response.code()} for $feedUrl")
        }

        val body = response.body()
            ?: throw RssParseException("Playlist feed response for $feedUrl had no body")

        body.byteStream().use { stream -> parser.parse(stream) }
    }
}

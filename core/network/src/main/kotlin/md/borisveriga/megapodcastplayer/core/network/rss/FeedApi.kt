package md.borisveriga.megapodcastplayer.core.network.rss

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Fetches arbitrary RSS feeds.
 *
 * Feeds live on hosts we do not control, so the URL is passed per call with [Url]. The response is
 * [Streaming] and typed as a raw [ResponseBody] because [RssParser] consumes the byte stream
 * directly — a large feed is never materialised as a string.
 */
interface FeedApi {

    /**
     * Conditionally downloads a feed.
     *
     * @param url the absolute feed URL.
     * @param etag the `ETag` from the previous fetch, or null on the first fetch.
     * @param lastModified the `Last-Modified` from the previous fetch, or null.
     * @return the raw response; `304 Not Modified` when nothing changed since the last fetch.
     */
    @Streaming
    @GET
    suspend fun getFeed(
        @Url url: String,
        @Header("If-None-Match") etag: String?,
        @Header("If-Modified-Since") lastModified: String?,
    ): Response<ResponseBody>
}

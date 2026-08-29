package md.borisveriga.bpodcat.core.youtube

import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * The desktop User-Agent extraction runs under.
 *
 * Both the extractor's own calls and the resolved audio URL are fetched with this string: the media
 * host occasionally validates that the two agree, and a mismatch shows up as an unexplained 403 part
 * way into playback rather than as a clean failure at resolution time.
 */
internal const val YOUTUBE_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/128.0.0.0 Safari/537.36"

/**
 * Bridges NewPipe's [Downloader] onto the app's shared [OkHttpClient].
 *
 * The extractor needs an HTTP client and would otherwise bring its own; reusing the injected one
 * means extraction shares the connection pool, DNS cache and timeouts with feeds and artwork, which
 * is the entire reason that client is a singleton.
 *
 * @property client the shared, qualified OkHttp client.
 */
internal class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    /**
     * Performs one extractor request.
     *
     * @param request the extractor's request.
     * @return the response, with the body already read into memory — NewPipe's API is string-based,
     *   and these are small JSON and HTML documents.
     * @throws ReCaptchaException on HTTP 429, which is what the extractor's callers watch for to
     *   report rate limiting rather than a generic failure.
     * @throws java.io.IOException on network failure.
     */
    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod()
        val body = request.dataToSend()?.toRequestBody()

        val builder = OkHttpRequest.Builder()
            .url(url)
            // OkHttp rejects a body on GET/HEAD, and NewPipe only ever sends one with POST.
            .method(method, body)

        var hasUserAgent = false
        for ((name, values) in request.headers()) {
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            for (value in values) {
                builder.addHeader(name, value)
            }
        }
        if (!hasUserAgent) {
            builder.addHeader("User-Agent", YOUTUBE_USER_AGENT)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                throw ReCaptchaException("reCaptcha challenge requested", url)
            }

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                // OkHttp has already followed redirects, so this is where the bytes came from.
                // The extractor uses it to resolve relative URLs it finds in the body.
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

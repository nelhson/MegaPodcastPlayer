package md.borisveriga.megapodcastplayer.core.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.model.isPlayableMediaUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Goes straight to the publisher's file instead of through a download-measurement redirector, and
 * falls back to the redirector only when going straight does not work.
 *
 * ## Why this exists
 *
 * Many feeds publish their enclosures behind a "prefix": `https://dts.podtrac.com/redirect.mp3/`
 * followed by the real address, often with two or three such services stacked. Each one answers
 * with nothing but a redirect to the next, and each one logs the listener's IP address, the episode
 * and the time on the way. The real address is right there in the path, so the redirectors can be
 * skipped entirely: the request goes to the host that actually serves the file, and the services in
 * front of it never hear of it.
 *
 * ## Why it is safe to do here
 *
 * An enclosure URL is an identity, not an address (see CLAUDE.md): it is what the database stores
 * and what Media3 keys its cache and its downloads on. This interceptor changes neither. It rewrites
 * the request as it leaves, below everything that remembers URLs, so the stored URL, the cache key
 * and the download id all stay exactly as the feed spelled them.
 *
 * ## The fallback
 *
 * Stripping a prefix is a reading of someone else's URL scheme, and a publisher is free to make the
 * redirector part of how its file is reached. So a direct attempt that fails — a transport error, or
 * any status of 400 or above — is discarded and the original URL is requested as published. That
 * costs one wasted round trip on a genuine 404, which is rare for an enclosure and cheaper than an
 * episode that will not play. A cancelled call is never retried.
 *
 * Placed before [HttpsUpgradeInterceptor] in the chain, so the rewritten URL is upgraded to TLS
 * exactly as a published one would be.
 *
 * ## What it does not do
 *
 * As an *application* interceptor it sees the URL the caller asked for, not the redirects OkHttp
 * follows on its behalf, so a publisher's own host that redirects *into* a measurement service is
 * not caught. Every known prefix is written into the feed itself, which is the case covered.
 */
@Singleton
class TrackingPrefixInterceptor @Inject constructor() : Interceptor {

    /**
     * Requests the untracked URL where there is one, falling back to the original.
     *
     * @param chain the interceptor chain.
     * @return the direct response where it succeeded, the original's otherwise.
     * @throws IOException if the original request fails too; a direct attempt's failure is attached
     *   as a suppressed exception, so the log still shows what was tried first.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val direct = request.takeIf { it.method in STRIPPABLE_METHODS }
            ?.let { untrackedUrlOrNull(it.url) }
            ?: return chain.proceed(request)

        val directFailure: IOException? = try {
            val response = chain.proceed(request.newBuilder().url(direct).build())
            if (response.code < FIRST_FAILURE_STATUS) return response
            response.close()
            null
        } catch (failure: IOException) {
            // A cancelled call must stop here rather than spend a second request on nobody.
            if (chain.call().isCanceled()) throw failure
            failure
        }

        return try {
            chain.proceed(request)
        } catch (originalFailure: IOException) {
            directFailure?.let(originalFailure::addSuppressed)
            throw originalFailure
        }
    }

    internal companion object {

        /**
         * Methods that may be rewritten.
         *
         * The fallback re-sends the request, which is only unambiguously correct for safe, bodyless
         * methods. Every enclosure request — whole file or a range within it — is a GET.
         */
        private val STRIPPABLE_METHODS = setOf("GET", "HEAD")

        /** The lowest status that sends a direct attempt back to the published URL. */
        private const val FIRST_FAILURE_STATUS = 400

        /**
         * How many stacked prefixes are unwrapped.
         *
         * Three is common in the wild and five has been seen; the bound exists so that a URL built to
         * nest forever cannot keep this busy, not because more would be wrong.
         */
        private const val MAX_PREFIXES = 8

        /**
         * The known redirectors, by host, with how many leading path segments belong to each.
         *
         * What follows those segments is the real address, scheme usually omitted. A segment that is
         * not fixed — Chartable's and Magellan's per-show ids — is still counted, since it is always
         * exactly one segment.
         */
        private val PREFIXES: Map<String, PrefixShape> = mapOf(
            // dts.podtrac.com/redirect.mp3/<real> — the extension varies with the file.
            "dts.podtrac.com" to PrefixShape(segments = 1, firstSegmentStartsWith = "redirect."),
            // www.podtrac.com/pts/redirect.mp3/<real>
            "www.podtrac.com" to PrefixShape(segments = 2, firstSegment = "pts"),
            "podtrac.com" to PrefixShape(segments = 2, firstSegment = "pts"),
            // chtbl.com/track/<id>/<real> — Chartable, closed in 2024; its prefixes outlive it.
            "chtbl.com" to PrefixShape(segments = 2, firstSegment = "track"),
            // pdst.fm/e/<real> and its successor prfx.byspotify.com/e/<real>.
            "pdst.fm" to PrefixShape(segments = 1, firstSegment = "e"),
            "prfx.byspotify.com" to PrefixShape(segments = 1, firstSegment = "e"),
            // op3.dev/e/<real>, or op3.dev/e,pg=<guid>/<real> with options in the segment.
            "op3.dev" to PrefixShape(segments = 1, firstSegmentStartsWith = "e"),
            // pscrb.fm/rss/p/<real> and verifi.podscribe.com/rss/p/<real>.
            "pscrb.fm" to PrefixShape(segments = 2, firstSegment = "rss"),
            "verifi.podscribe.com" to PrefixShape(segments = 2, firstSegment = "rss"),
            // mgln.ai/e/<id>/<real>
            "mgln.ai" to PrefixShape(segments = 2, firstSegment = "e"),
            // clrtpod.com/m/<real>
            "clrtpod.com" to PrefixShape(segments = 1, firstSegment = "m"),
            // arttrk.com/p/<id>/<real>
            "arttrk.com" to PrefixShape(segments = 2, firstSegment = "p"),
            // tracking.swap.fm/track/<id>/<real>
            "tracking.swap.fm" to PrefixShape(segments = 2, firstSegment = "track"),
        )

        /**
         * The URL [url] points at once every known measurement prefix is removed, or null when it
         * carries none.
         *
         * Conservative by construction: a prefix is only removed when what follows it reads as a
         * host name, so a redirector whose path happens to look different from what is listed here
         * is left alone rather than turned into nonsense. The query string is kept, because it
         * belongs to the real URL — the redirectors pass it through.
         *
         * Extracted so the policy can be asserted directly, without standing up a server.
         *
         * @param url the URL as requested.
         * @return the direct URL, or null to request [url] unchanged.
         */
        fun untrackedUrlOrNull(url: HttpUrl): HttpUrl? {
            var current = url
            repeat(MAX_PREFIXES) {
                val next = stripOne(current) ?: return current.takeIf { it != url }
                current = next
            }
            return current.takeIf { it != url }
        }

        /**
         * [url] without its outermost prefix, or null when it has none.
         *
         * @param url a URL that may begin with a measurement prefix.
         */
        private fun stripOne(url: HttpUrl): HttpUrl? {
            val shape = PREFIXES[url.host]
            // Encoded, so that what is reassembled below is the URL as published, escapes and all.
            val segments = url.encodedPathSegments
            val isPrefixed = shape != null &&
                segments.size > shape.segments &&
                shape.matches(segments.first())
            return if (isPrefixed) {
                directUrlOrNull(url.scheme, segments.drop(shape.segments), url.encodedQuery)
            } else {
                null
            }
        }

        /**
         * The URL that the path after a prefix spells out, or null when it does not spell one.
         *
         * Some prefixes carry the real scheme: `op3.dev/e/https://host/path` splits into `https:`,
         * an empty segment and the host. Without one, the redirector's own scheme is kept, and
         * [HttpsUpgradeInterceptor] upgrades a cleartext one exactly as it would a published URL.
         *
         * @param prefixScheme the redirector's own scheme.
         * @param rest the encoded path segments after the prefix; never empty.
         * @param encodedQuery the query string, which belongs to the real URL.
         */
        private fun directUrlOrNull(
            prefixScheme: String,
            rest: List<String>,
            encodedQuery: String?,
        ): HttpUrl? {
            val embedded = rest.first().takeIf { it.endsWith(':') }?.dropLast(1)?.lowercase()
            val hasEmbeddedScheme = embedded in HTTP_SCHEMES
            val scheme = if (hasEmbeddedScheme) embedded else prefixScheme
            val target = if (hasEmbeddedScheme) rest.drop(EMBEDDED_SCHEME_SEGMENTS) else rest
            // An embedded scheme must be followed by the empty segment `//` makes, then a host.
            val wellFormed = target.isNotEmpty() &&
                (!hasEmbeddedScheme || rest[1].isEmpty()) &&
                looksLikeHost(target.first().substringBefore(':'))

            val candidate = "$scheme://${target.joinToString(separator = "/")}" +
                encodedQuery?.let { "?$it" }.orEmpty()
            return candidate.takeIf { wellFormed && isPlayableMediaUrl(it) }?.toHttpUrlOrNull()
        }

        /**
         * Whether [segment] can be the host of the real URL: at least two dot-separated labels of
         * letters, digits and hyphens, the last of them not all digits unless the whole is an IPv4
         * address.
         *
         * The check that keeps a misread prefix from producing a request to `redirect.mp3` or `e`.
         */
        private fun looksLikeHost(segment: String): Boolean {
            val labels = segment.split('.')
            if (labels.size < 2) return false
            val wellFormed = labels.all { label ->
                label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' }
            }
            if (!wellFormed) return false
            val isIpv4 = labels.size == IPV4_PARTS && labels.all { label -> label.all(Char::isDigit) }
            return isIpv4 || labels.last().any(Char::isLetter)
        }

        /** An embedded scheme and the empty segment its `//` leaves behind. */
        private const val EMBEDDED_SCHEME_SEGMENTS = 2

        /** The two schemes a prefix may embed. */
        private val HTTP_SCHEMES = setOf("http", "https")

        /** How many parts an IPv4 address has. */
        private const val IPV4_PARTS = 4
    }

    /**
     * How a redirector's own part of the path is recognised.
     *
     * @property segments how many leading path segments the prefix occupies.
     * @property firstSegment the exact first segment, when it is fixed.
     * @property firstSegmentStartsWith what the first segment begins with, when only that is fixed.
     */
    private class PrefixShape(
        val segments: Int,
        val firstSegment: String? = null,
        val firstSegmentStartsWith: String? = null,
    ) {
        /** Whether [segment], the URL's first path segment, is this prefix's. */
        fun matches(segment: String): Boolean = when {
            firstSegment != null -> segment == firstSegment
            firstSegmentStartsWith != null -> segment.startsWith(firstSegmentStartsWith)
            else -> true
        }
    }
}

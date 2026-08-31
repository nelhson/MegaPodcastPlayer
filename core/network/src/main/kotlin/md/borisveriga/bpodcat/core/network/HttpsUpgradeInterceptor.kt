package md.borisveriga.bpodcat.core.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Tries every `http://` request over `https://` first, and only falls back to cleartext when the
 * secure attempt fails at the transport level.
 *
 * ## Why this exists
 *
 * Podcast enclosures are not written by us. A large share of the feeds in the wild route their
 * audio through a cleartext measurement redirector — `http://dts.podtrac.com/redirect.mp3/...` is
 * the common one, and Chartable, Blubrry and Feedburner all publish the same shape — which then
 * bounces to the publisher's real CDN. Since `targetSdk` 28 Android refuses cleartext by default,
 * so those shows parse, list and look perfectly healthy, and then fail the moment the user presses
 * play. See `network_security_config.xml`, which is what permits the fallback below to happen at
 * all.
 *
 * Simply switching cleartext back on would answer the bug, but it would also send every byte of
 * everyone's listening history over plain HTTP, including the shows that were already reachable
 * over TLS. The redirectors overwhelmingly *do* serve HTTPS — they just publish `http://` links for
 * historical reasons — so upgrading first means the cleartext permission is exercised only by the
 * hosts that genuinely have nothing else to offer.
 *
 * ## What it does not do
 *
 * This is an *application* interceptor, so it sees the URL the caller asked for, not the hops
 * OkHttp follows on its behalf. A redirect that lands on `http://` mid-chain still travels in
 * cleartext; that is exactly the case the network security config has to cover, and it cannot be
 * fixed here — a network interceptor is handed a connection that is already established.
 *
 * A host that answers on 443 with unrelated content (a parking page, a wrong virtual host) is also
 * out of scope: the fallback triggers on transport failures, not on a valid HTTP response with an
 * unhelpful status. Reacting to statuses would mean paying for two round trips on every genuine
 * 404, and would make a transient 503 silently downgrade the connection.
 */
@Singleton
class HttpsUpgradeInterceptor @Inject constructor() : Interceptor {

    /**
     * Upgrades and, if necessary, retries the call.
     *
     * @param chain the interceptor chain.
     * @return the response, from the secure attempt where possible.
     * @throws IOException if both attempts fail; the HTTPS failure is attached as a suppressed
     *   exception so a crash report still shows why the upgrade did not take.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isUpgradable(request.url.scheme, request.url.port, request.method)) {
            return chain.proceed(request)
        }

        val secure = request.newBuilder()
            .url(request.url.newBuilder().scheme(HTTPS_SCHEME).build())
            .build()

        return try {
            chain.proceed(secure)
        } catch (secureFailure: IOException) {
            try {
                chain.proceed(request)
            } catch (cleartextFailure: IOException) {
                cleartextFailure.addSuppressed(secureFailure)
                throw cleartextFailure
            }
        }
    }

    internal companion object {

        /** The scheme requests are rewritten to. */
        const val HTTPS_SCHEME = "https"

        /** The scheme requests are rewritten from. */
        private const val HTTP_SCHEME = "http"

        /**
         * The only port an upgrade is attempted on.
         *
         * `HttpUrl.newBuilder()` drops a port that is the default for the current scheme, so
         * rewriting `http://host/x` yields `https://host/x` on 443 as intended. An explicit
         * non-default port is a different matter: `http://host:8080/x` would become
         * `https://host:8080/x`, pointing TLS at a port nobody promised would speak it. Those are
         * left alone.
         */
        private const val DEFAULT_HTTP_PORT = 80

        /**
         * Methods that may be upgraded.
         *
         * Restricted to the safe, bodyless ones because the fallback re-sends the request, and
         * re-sending is only unambiguously correct when the request carries no body and the server
         * was promised no side effects. Every request this app makes — feeds, artwork, audio and
         * range requests within it — is a GET.
         */
        private val UPGRADABLE_METHODS = setOf("GET", "HEAD")

        /**
         * Whether a request with these properties should be attempted over TLS first.
         *
         * Extracted so the policy can be asserted directly, without standing up a server.
         *
         * @param scheme the request scheme, lowercased by `HttpUrl`.
         * @param port the effective port.
         * @param method the HTTP method.
         */
        fun isUpgradable(scheme: String, port: Int, method: String): Boolean =
            scheme == HTTP_SCHEME && port == DEFAULT_HTTP_PORT && method in UPGRADABLE_METHODS
    }
}

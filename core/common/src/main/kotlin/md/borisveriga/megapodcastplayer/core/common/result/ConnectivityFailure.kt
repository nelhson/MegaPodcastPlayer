package md.borisveriga.megapodcastplayer.core.common.result

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Whether this failure means the network was not there, rather than that something is wrong.
 *
 * The distinction matters to [md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter].
 * A phone in a tunnel, or a backgrounded app that Android has cut off from the network, fails every
 * request it makes at once, and every one of those failures looks like a bug in the frame that made
 * the request. None of them is: it is the device's state, and reporting it buries the failures that
 * are worth reading under a pile of DNS lookups.
 *
 * What counts:
 *
 *  - [UnknownHostException] — no DNS. `EAI_NODATA` in particular is what a backgrounded app gets
 *    when the system is blocking its network access, even with a connection up.
 *  - [SocketException] — the connection could not be made or was torn down under us: refused, no
 *    route, reset, "software caused connection abort" when the network changes mid-read.
 *  - [SocketTimeoutException] — nothing came back in time.
 *
 * What does not: an HTTP error status, an unparseable body, a TLS certificate problem. Those are a
 * server's answer, and a server that answers wrongly is worth knowing about.
 *
 * The cause chain is searched, not just this throwable, because a parser or a library that wraps
 * its I/O errors still failed for the same reason.
 */
val Throwable.isConnectivityFailure: Boolean
    get() = generateSequence(this, Throwable::cause)
        // `Throwable.cause` already returns null for a self-cause; the cap covers a longer cycle,
        // which `initCause` does not prevent.
        .take(MAX_CAUSE_DEPTH)
        .any { it is UnknownHostException || it is SocketException || it is SocketTimeoutException }

/** How far down a cause chain [isConnectivityFailure] looks before giving up. */
private const val MAX_CAUSE_DEPTH = 16

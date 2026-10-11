package md.borisveriga.megapodcastplayer.core.common.crash

/**
 * Somewhere to send a failure that nobody is watching.
 *
 * Most of this app's failures are handled by carrying on: a feed that will not parse is skipped so
 * the other nineteen still refresh, a Data Layer write with no watch in range is simply lost, a
 * transfer that dies mid-file is retried on the next tap. That is the right behaviour for the user
 * and it is why `suspendRunCatching` is used so widely — but it means the only trace of a bug is a
 * `Result.failure` that nothing reads. This interface is where those go.
 *
 * ## Nothing leaves the device
 *
 * The one production implementation, [LogcatCrashReporter], writes to the device log and nowhere
 * else. There is no crash-reporting service on the classpath, deliberately: a report would carry
 * YouTube video and playlist ids, feed URLs and stack traces naming the extractor, and none of that
 * is anyone else's business. A failure is read over `adb logcat`, on a device that is plugged in.
 *
 * ## What belongs here
 *
 * A failure that a person would want to know about after the fact, and that the code has already
 * decided not to show anyone. Not: expected outcomes (a search with no results, or a request made
 * with no network — see `isConnectivityFailure`), and not user mistakes (a URL that is not a feed).
 *
 * Every implementation must be safe to call from any thread and must never throw: a reporter that
 * failed while reporting a failure would turn a survivable bug into a crash.
 */
interface CrashReporter {

    /**
     * Records a failure that was handled without telling the user.
     *
     * Give the *operation* that failed and keep it constant per call site — "podcast refresh
     * failed", not the feed's title — so that one fault reads as one line in the log however many
     * times it happens.
     *
     * @param message a short, fixed description of what was being attempted.
     * @param throwable the failure, kept whole; its stack trace is what makes the record useful.
     */
    fun recordNonFatal(message: String, throwable: Throwable)

    /**
     * Records a decision worth seeing next to the failures around it.
     *
     * Log decisions ("resuming at 0:42 from the watch's position") rather than progress.
     *
     * @param message the line to record.
     */
    fun log(message: String)
}

/**
 * A [CrashReporter] that discards everything.
 *
 * The default in unit tests. It is an object rather than a mock so that a test which does not care
 * about reporting does not have to say so.
 */
object NoOpCrashReporter : CrashReporter {
    override fun recordNonFatal(message: String, throwable: Throwable) = Unit
    override fun log(message: String) = Unit
}

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
 * ## What belongs here
 *
 * A failure that a person would want to know about after the fact, and that the code has already
 * decided not to show anyone. Not: expected outcomes (a search with no results), not user mistakes
 * (a URL that is not a feed), and not anything on a hot path — every call crosses into a native
 * library and writes to disk.
 *
 * ## Why an interface rather than calling Firebase
 *
 * Only `:core:common` knows Firebase exists. Feature and core modules depend on this interface, so
 * they stay testable without a Firebase project, unit tests get [NoOpCrashReporter] instead of a
 * network client, and replacing the backend is a one-file change.
 *
 * Every implementation must be safe to call from any thread and must never throw: a reporter that
 * failed while reporting a failure would turn a survivable bug into a crash.
 */
interface CrashReporter {

    /**
     * Whether anything said to this reporter actually leaves the device.
     *
     * False in a build with no Firebase configuration, which is a supported build rather than a
     * broken one — see `CrashModule`. Published because an app with a crash reporter on its
     * classpath should be able to say so where the user can read it (SET-4), and "it depends on
     * whether a JSON file was present at build time" is not an answer a settings screen can give
     * without asking.
     */
    val isReporting: Boolean

    /**
     * Records a failure that was handled without telling the user.
     *
     * Reports are grouped by [message] rather than by stack trace, so give the *operation* that
     * failed and keep it constant per call site — "podcast refresh failed", not the feed's title.
     * Anything variable belongs in [key].
     *
     * @param message a short, fixed description of what was being attempted.
     * @param throwable the failure, kept whole; its stack trace is what makes the report useful.
     */
    fun recordNonFatal(message: String, throwable: Throwable)

    /**
     * Attaches a value to every report this process sends from now on.
     *
     * Use it for state that explains a crash rather than for events: which screen is open, whether
     * a watch is connected, how many episodes are downloaded. Keys are overwritten, not appended.
     *
     * @param key a short, stable name.
     * @param value the value; implementations may truncate it.
     */
    fun setKey(key: String, value: String)

    /**
     * Appends a line to the breadcrumb log carried by the *next* report.
     *
     * Cheap relative to [recordNonFatal] but not free, and the log is capped, so log decisions
     * ("resuming at 0:42 from the watch's position") rather than progress.
     *
     * @param message the line to record. Must contain nothing private to the user.
     */
    fun log(message: String)
}

/**
 * A [CrashReporter] that discards everything.
 *
 * The default in unit tests, and the binding used by any build with no Firebase configuration. It
 * is an object rather than a mock so that a test which does not care about reporting does not have
 * to say so.
 */
object NoOpCrashReporter : CrashReporter {
    override val isReporting: Boolean = false
    override fun recordNonFatal(message: String, throwable: Throwable) = Unit
    override fun setKey(key: String, value: String) = Unit
    override fun log(message: String) = Unit
}

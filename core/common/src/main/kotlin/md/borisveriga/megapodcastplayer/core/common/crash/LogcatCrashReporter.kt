package md.borisveriga.megapodcastplayer.core.common.crash

import android.util.Log

/**
 * The [CrashReporter] every build uses: the device log, and nothing past it.
 *
 * Failures go to logcat at `WARN` with their whole stack trace, decisions at `INFO`, all under one
 * tag so `adb logcat -s MegaPodcastPlayer` shows exactly this and nothing else. The log is a ring
 * buffer that lives on the device, so a failure from yesterday is gone by today; that is the price
 * of sending it nowhere, and it is paid on purpose — see [CrashReporter].
 *
 * Uncaught exceptions need nothing from this class: the platform already writes them to the same
 * log before the process dies.
 */
internal class LogcatCrashReporter : CrashReporter {

    override fun recordNonFatal(message: String, throwable: Throwable) = guarded {
        Log.w(TAG, message, throwable)
    }

    override fun log(message: String) = guarded {
        Log.i(TAG, message)
    }

    /**
     * Runs [block], swallowing anything it throws.
     *
     * The contract in [CrashReporter] is that reporting a failure must never itself fail: these
     * calls happen on paths that have already decided to carry on. Logcat does not throw on a
     * device, but a log call made where there is no Android runtime does, and there is nowhere
     * useful to report the failure of the reporter.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            // Deliberately empty; see the KDoc above.
        }
    }

    internal companion object {
        /** The one tag every line from this reporter carries. */
        const val TAG = "MegaPodcastPlayer"
    }
}

package md.borisveriga.megapodcastplayer.core.common.crash

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.firebase.crashlytics.FirebaseCrashlytics
import md.borisveriga.megapodcastplayer.core.common.R

/**
 * The [CrashReporter] that actually sends things: Firebase Crashlytics.
 *
 * Uncaught exceptions and ANRs need no code at all — the SDK installs its own
 * [Thread.UncaughtExceptionHandler] from a content provider before [android.app.Application.onCreate]
 * runs, which is why crashes during start-up are still caught. Everything on this class is for the
 * failures that never become an uncaught exception.
 *
 * ## Reporting is on in debug builds
 *
 * The usual advice is to switch collection off for debug, on the assumption that debug builds run
 * on a developer's desk and release builds run on phones. Here it is the other way round: the
 * `install_on_devices` skill sideloads debug APKs onto the Fold and the Watch, and those are the
 * builds this app actually lives in. Reporting is therefore left on, and every report carries a
 * `buildType` key so a crash from a desk can be told from a crash from a pocket. There are three
 * values now, not two: `debug`, `release`, and `wrist` — the shrunk, debug-signed build the watch
 * runs, which is not debuggable and so would pass for a release by the manifest flag alone.
 *
 * @property crashlytics the SDK singleton. Injected rather than fetched so tests can substitute it.
 * @param context used once, to read which build type this APK says it is.
 */
internal class FirebaseCrashReporter(
    private val crashlytics: FirebaseCrashlytics,
    context: Context,
) : CrashReporter {

    init {
        // Neither is a BuildConfig: this class lives in a library module, whose BuildConfig
        // describes the library's own variant and not the APK's. The application writes its build
        // type's name into a string resource (see `nameBuildTypesForCrashReports` in build-logic);
        // the manifest flag is what is left when it has not.
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setKey(KEY_BUILD_TYPE, buildTypeTag(declared = declaredBuildType(context), debuggable = debuggable))
    }

    /**
     * The build type the application wrote into its resources, or blank.
     *
     * Read under the same guard as every SDK call: a resource lookup that failed here would
     * otherwise fail the construction of the crash reporter, in the application's `onCreate`.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun declaredBuildType(context: Context): String = try {
        context.getString(R.string.crash_build_type)
    } catch (throwable: Throwable) {
        ""
    }

    override val isReporting: Boolean = true

    override fun recordNonFatal(message: String, throwable: Throwable) = guarded {
        // Logged first so the message is attached to this report and not merely to the next one:
        // Crashlytics groups non-fatals by the exception's top stack frame, so without it two
        // unrelated failures that both come out of the same `await()` would land in one issue.
        crashlytics.log(message)
        crashlytics.recordException(throwable)
    }

    override fun setKey(key: String, value: String) = guarded {
        crashlytics.setCustomKey(key, value)
    }

    override fun log(message: String) = guarded {
        crashlytics.log(message)
    }

    /**
     * Runs [block], swallowing anything it throws.
     *
     * The contract in [CrashReporter] is that reporting a failure must never itself fail: these
     * calls happen on paths that have already decided to carry on, and a process killed by its own
     * crash reporter would be a strictly worse outcome than the bug being reported. There is
     * nowhere useful to report the failure of the reporter, so it is dropped.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            // Deliberately empty; see the KDoc above.
        }
    }

    private companion object {
        /** Distinguishes a sideloaded debug or wrist build from a signed release one. */
        const val KEY_BUILD_TYPE = "buildType"
    }
}

/**
 * The value of the `buildType` key on every report.
 *
 * What the application declared, when it declared anything; otherwise what the manifest's
 * debuggable flag implies, which is right for the two build types that existed before `wrist` and
 * is the answer for an application module that has not been told to name its build types.
 *
 * @param declared the build type name the application wrote into its resources, or blank.
 * @param debuggable whether the APK carries `FLAG_DEBUGGABLE`.
 */
internal fun buildTypeTag(declared: String, debuggable: Boolean): String =
    declared.ifBlank { if (debuggable) "debug" else "release" }

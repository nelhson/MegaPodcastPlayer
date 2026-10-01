package md.borisveriga.megapodcastplayer.core.common.crash

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [FirebaseCrashReporter].
 *
 * The SDK is mocked rather than started: there is no Firebase project in a unit test, and what is
 * worth pinning here is not that Crashlytics works but that this class holds up its end of the
 * [CrashReporter] contract — that it never throws, and that a report carries enough to be read.
 */
class FirebaseCrashReporterTest {

    private val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)

    @Test
    fun `a debug APK is tagged as debug`() {
        FirebaseCrashReporter(crashlytics, context(debuggable = true))

        verify { crashlytics.setCustomKey("buildType", "debug") }
    }

    @Test
    fun `a release APK is tagged as release`() {
        FirebaseCrashReporter(crashlytics, context(debuggable = false))

        verify { crashlytics.setCustomKey("buildType", "release") }
    }

    /**
     * The case the debuggable flag gets wrong. The watch's daily build is shrunk and not
     * debuggable, so by the flag alone its crashes would be filed as a release's — the one build
     * they most need telling apart from.
     */
    @Test
    fun `an APK that names its build type is tagged with that name`() {
        FirebaseCrashReporter(crashlytics, context(debuggable = false, declared = "wrist"))

        verify { crashlytics.setCustomKey("buildType", "wrist") }
        verify(exactly = 0) { crashlytics.setCustomKey("buildType", "release") }
    }

    @Test
    fun `the declared name wins over the debuggable flag, and a blank one does not`() {
        assertEquals("wrist", buildTypeTag(declared = "wrist", debuggable = false))
        assertEquals("debug", buildTypeTag(declared = "debug", debuggable = true))
        assertEquals("debug", buildTypeTag(declared = "", debuggable = true))
        assertEquals("release", buildTypeTag(declared = " ", debuggable = false))
    }

    /** A resource lookup that throws must not fail the reporter's construction. */
    @Test
    fun `an unreadable build type falls back to the flag`() {
        val context = context(debuggable = true)
        every { context.getString(any()) } throws IllegalStateException("no resources")

        FirebaseCrashReporter(crashlytics, context)

        verify { crashlytics.setCustomKey("buildType", "debug") }
    }

    @Test
    fun `a non-fatal is logged before it is recorded`() {
        val reporter = reporter()
        val failure = IOException("host unreachable")

        reporter.recordNonFatal("Podcast refresh failed", failure)

        // Order matters: Crashlytics attaches the log to the report that follows it, so a message
        // logged afterwards would describe the *next* failure instead of this one.
        verifyOrder {
            crashlytics.log("Podcast refresh failed")
            crashlytics.recordException(failure)
        }
    }

    @Test
    fun `the throwable is passed through unchanged`() {
        val reporter = reporter()
        val cause = IllegalStateException("cache gap")
        val failure = IOException("transfer stopped short", cause)

        reporter.recordNonFatal("Wear audio transfer incomplete", failure)

        // Not wrapped and not replaced by its message: the stack trace and the cause chain are the
        // only parts of a non-fatal that say where it came from.
        verify { crashlytics.recordException(failure) }
    }

    @Test
    fun `an SDK that throws does not take the caller down with it`() {
        // The situation this guards: Crashlytics half-initialised, or its disk full. Every call
        // site has already decided to carry on from a failure, so the reporter must not be the
        // thing that finally kills the process.
        every { crashlytics.log(any()) } throws IllegalStateException("Crashlytics not initialised")
        every { crashlytics.recordException(any()) } throws IllegalStateException("no")
        every { crashlytics.setCustomKey(any<String>(), any<String>()) } throws IllegalStateException("no")

        val reporter = FirebaseCrashReporter(crashlytics, context(debuggable = true))

        reporter.log("still fine")
        reporter.setKey("feedUrl", "https://example.com/feed.rss")
        reporter.recordNonFatal("Podcast refresh failed", IOException("boom"))
    }

    @Test
    fun `keys and logs reach the SDK verbatim`() {
        val reporter = reporter()

        reporter.setKey("feedUrl", "https://example.com/feed.rss")
        reporter.log("resuming from the watch's position")

        verify { crashlytics.setCustomKey("feedUrl", "https://example.com/feed.rss") }
        verify { crashlytics.log("resuming from the watch's position") }
    }

    @Test
    fun `the no-op reporter accepts everything and does nothing`() {
        // Asserted because it is the binding a build with no Firebase configuration gets, and a
        // reporter that threw there would break exactly the builds it exists to keep working.
        NoOpCrashReporter.log("anything")
        NoOpCrashReporter.setKey("k", "v")
        NoOpCrashReporter.recordNonFatal("failed", IOException("boom"))

        assertEquals(Unit, NoOpCrashReporter.log("still nothing"))
    }

    /** A reporter whose construction-time key writes have already been made and forgotten. */
    private fun reporter() = FirebaseCrashReporter(crashlytics, context(debuggable = false))

    /**
     * A [Context] that reports whether its APK carries `FLAG_DEBUGGABLE` and what build type it
     * names itself.
     *
     * Those two are all [FirebaseCrashReporter] reads from a context, so nothing else is stubbed.
     *
     * @param debuggable whether the manifest flag is set.
     * @param declared the build type name in the APK's resources; blank is what a library on its
     *   own, or an application that names nothing, has.
     */
    private fun context(debuggable: Boolean, declared: String = ""): Context {
        val info = ApplicationInfo().apply {
            flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0
        }
        return mockk<Context> {
            every { applicationInfo } returns info
            every { getString(any()) } returns declared
        }
    }
}

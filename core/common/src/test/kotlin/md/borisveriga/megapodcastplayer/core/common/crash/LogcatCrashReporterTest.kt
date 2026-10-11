package md.borisveriga.megapodcastplayer.core.common.crash

import android.util.Log
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Tests for [LogcatCrashReporter].
 *
 * Robolectric's [ShadowLog] stands in for logcat, so what is pinned is what a person reading
 * `adb logcat` would see: one tag, the failure at warning level with its throwable intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogcatCrashReporterTest {

    private val reporter = LogcatCrashReporter()

    @Before
    fun clearLog() {
        ShadowLog.clear()
    }

    @Test
    fun `a non-fatal is a warning carrying the throwable unchanged`() {
        val failure = IOException("transfer stopped short", IllegalStateException("cache gap"))

        reporter.recordNonFatal("Podcast refresh failed", failure)

        val entry = ShadowLog.getLogsForTag(LogcatCrashReporter.TAG).single()
        assertEquals(Log.WARN, entry.type)
        assertEquals("Podcast refresh failed", entry.msg)
        // Not wrapped and not reduced to its message: the stack trace and cause chain are the only
        // parts of a non-fatal that say where it came from.
        assertSame(failure, entry.throwable)
    }

    @Test
    fun `a breadcrumb is an info line under the same tag`() {
        reporter.log("Phone process started")

        val entry = ShadowLog.getLogsForTag(LogcatCrashReporter.TAG).single()
        assertEquals(Log.INFO, entry.type)
        assertEquals("Phone process started", entry.msg)
    }

    @Test
    fun `the no-op reporter accepts everything and does nothing`() {
        NoOpCrashReporter.log("anything")
        NoOpCrashReporter.recordNonFatal("failed", IOException("boom"))

        assertEquals(emptyList<ShadowLog.LogItem>(), ShadowLog.getLogsForTag(LogcatCrashReporter.TAG))
    }
}

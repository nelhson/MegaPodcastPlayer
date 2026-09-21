package md.borisveriga.megapodcastplayer.wear.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * Tests the record of what the watch has already explained.
 *
 * Small, and worth pinning anyway: the whole value of a one-time hint is the *one*, and the two
 * ways to get it wrong are a marker that is never written — a sentence that comes back on every
 * scrub — and one that is read as written on a fresh install, so the hint never appears at all.
 *
 * Robolectric only for the application context's files directory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WatchHintsTest {

    @Before
    fun forgetEverything() {
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        filesDir.resolve("scrub-hint-seen").delete()
        filesDir.resolve("volume-hint-seen").delete()
    }

    private fun hints() = WatchHints(
        context = RuntimeEnvironment.getApplication(),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `a watch that has never scrubbed has not seen the hint`() = runTest {
        assertFalse(hints().hasSeenScrubHint())
    }

    @Test
    fun `the hint is remembered once it has been shown`() = runTest {
        val hints = hints()

        hints.markScrubHintSeen()

        assertTrue(hints.hasSeenScrubHint())
    }

    @Test
    fun `being shown twice is not an error`() = runTest {
        val hints = hints()

        hints.markScrubHintSeen()
        hints.markScrubHintSeen()

        assertTrue(hints.hasSeenScrubHint())
    }

    /**
     * The two hints are two discoveries and two markers. Learning the bezel on the scrubber does
     * not teach that the volume bar can be taken hold of too, so showing one must not silence the
     * other.
     */
    @Test
    fun `learning to scrub does not count as learning the volume bar`() = runTest {
        val hints = hints()

        hints.markScrubHintSeen()

        assertFalse(hints.hasSeenVolumeHint())
    }

    @Test
    fun `the volume hint is remembered once it has been shown`() = runTest {
        val hints = hints()

        hints.markVolumeHintSeen()

        assertTrue(hints.hasSeenVolumeHint())
        assertFalse(hints.hasSeenScrubHint())
    }

    @Test
    fun `a new instance reads what an earlier one wrote`() = runTest {
        hints().markScrubHintSeen()

        // Which is the case that matters: the hint is shown in one process and must stay shown-in
        // the next, days later, after the watch has been rebooted.
        assertTrue(hints().hasSeenScrubHint())
    }
}

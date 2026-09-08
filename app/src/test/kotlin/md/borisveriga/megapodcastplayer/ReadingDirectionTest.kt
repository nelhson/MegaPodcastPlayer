package md.borisveriga.megapodcastplayer

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins DS-8's decision (§5.1, D-5): this app declares that it does not support right-to-left.
 *
 * A test for a manifest attribute looks like overkill until you ask what would go wrong without
 * one. `supportsRtl="true"` is the platform default in every template and the value an IDE
 * refactoring or a merge would restore without comment — and the moment it comes back, the app
 * claims a capability `SwipeActionsRow` does not deliver: it only opens leftwards and anchors its
 * buttons at `CenterEnd`, so a mirrored layout puts the buttons on one side and the gesture pulling
 * away from them. Nothing would fail. The rows would simply stop working, in a reading direction
 * nobody here runs.
 *
 * So the false is asserted rather than trusted, and this KDoc is where the day it becomes wrong is
 * explained: make `SwipeActionsRow` direction-aware first, then delete this test.
 */
@RunWith(AndroidJUnit4::class)
// The plain Application: the real one binds a Media3 controller on start-up, and this reads a flag.
@Config(sdk = [34], application = Application::class)
class ReadingDirectionTest {

    @Test
    fun `the app does not claim to support right-to-left`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val supportsRtl = context.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL

        assertEquals(
            "supportsRtl is true again; see DS-8 — the work is SwipeActionsRow's, not the manifest's",
            0,
            supportsRtl,
        )
    }
}

package md.borisveriga.megapodcastplayer.wear

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The watch's half of DS-8 (§5.1, D-5): it declares no right-to-left support either.
 *
 * The watch has no swipe rows, so nothing here would visibly break if it were mirrored. It is set
 * false all the same, and asserted here for the reason the decision gives: two halves of one app,
 * installed from one build, should not disagree about which reading directions they support. The
 * substance is on the phone's side; see `ReadingDirectionTest` in `:app`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ReadingDirectionTest {

    @Test
    fun `the watch app does not claim to support right-to-left either`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val supportsRtl = context.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL

        assertEquals(
            "supportsRtl is true again on the watch; the phone and the watch must agree (DS-8)",
            0,
            supportsRtl,
        )
    }
}

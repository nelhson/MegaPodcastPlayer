package md.borisveriga.megapodcastplayer.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [TopLevelDestination].
 *
 * The enum is small enough to read, but it is also the one place a new tab is added, and the two
 * ways of getting it wrong — pointing two tabs at the same route, or reusing a neighbour's label —
 * both produce a navigation bar that looks right and behaves wrongly. Both are cheap to pin here.
 *
 * `MegaPodcastPlayerApp` resolves each destination's label through the same resource ids, so a label that
 * fails to resolve fails here rather than as a blank tab on the device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TopLevelDestinationTest {

    private val resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `the bar holds the three lists the app is made of, in order`() {
        assertEquals(
            listOf("Library", "Queue", "Downloads"),
            TopLevelDestination.entries.map { resources.getString(it.labelResId) },
        )
    }

    @Test
    fun `every tab goes somewhere of its own`() {
        val routes = TopLevelDestination.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
    }
}

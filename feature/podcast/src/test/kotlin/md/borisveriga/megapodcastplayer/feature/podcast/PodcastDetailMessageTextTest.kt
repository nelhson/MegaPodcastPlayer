package md.borisveriga.megapodcastplayer.feature.podcast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the snackbar sentence a rebuild ends in.
 *
 * The one message on the show's page built from two counts, and so from two plurals joined by a
 * third string. Each half is singular or plural on its own, which is the thing a single format
 * string with both numbers in it gets wrong.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PodcastDetailMessageTextTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `a rebuild that kept nothing says only what it reloaded`() {
        assertEquals(
            "Reloaded 12 episodes",
            PodcastDetailMessage.Rebuilt(episodeCount = 12).toText(resources),
        )
    }

    @Test
    fun `a rebuild that kept downloads counts them after what it reloaded`() {
        // The list on screen is three longer than the first number, and the second sentence is
        // what says why.
        assertEquals(
            "Reloaded 12 episodes. Kept 3 downloads that are no longer listed.",
            PodcastDetailMessage.Rebuilt(episodeCount = 12, keptDownloadCount = 3).toText(resources),
        )
    }

    @Test
    fun `each count takes its own plural`() {
        assertEquals(
            "Reloaded 1 episode. Kept 1 download that is no longer listed.",
            PodcastDetailMessage.Rebuilt(episodeCount = 1, keptDownloadCount = 1).toText(resources),
        )
    }
}

package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [YouTubeSource].
 *
 * Two constants carry the whole contract: what an install does until told otherwise, and how many
 * videos the official source shows. Both are relied on far from here — the first by every install
 * that predates the setting, the second by the fetcher and the repository, which must agree on it.
 */
class YouTubeSourceTest {

    @Test
    fun `the default is the extractor, which is what every install did before the choice`() {
        assertEquals(YouTubeSource.EXTRACTOR, YouTubeSource.DEFAULT)
    }

    @Test
    fun `the official limit is ten`() {
        assertEquals(10, YouTubeSource.OFFICIAL_EPISODE_LIMIT)
    }

    @Test
    fun `the official limit is under the fifteen the feed returns, so the feed never decides it`() {
        assertTrue(YouTubeSource.OFFICIAL_EPISODE_LIMIT < 15)
    }

    @Test
    fun `the embedded player is an ask of its own`() {
        // The shell routes on this value; a build that lost it would open the app's player on an
        // official show's episode and hand Media3 a video it is not allowed to resolve.
        assertTrue(OpenPlayerAs.EMBEDDED in OpenPlayerAs.entries)
    }
}

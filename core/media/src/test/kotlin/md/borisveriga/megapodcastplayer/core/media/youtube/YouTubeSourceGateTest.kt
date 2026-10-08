package md.borisveriga.megapodcastplayer.core.media.youtube

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [YouTubeSourceGate].
 *
 * The gate is read on a Media3 thread without suspending, so what matters is that it answers at
 * all times: before the store has emitted (by blocking for the value), after it has, and after the
 * value changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeSourceGateTest {

    @Test
    fun `answers the default before the store has emitted`() = runTest {
        val preferences = UserPreferencesDataSource(InMemoryPreferencesDataStore())
        val gate = YouTubeSourceGate(preferences, backgroundScope)

        // No runCurrent: the mirror has not had a chance to fill, so this is the blocking path.
        assertEquals(YouTubeSource.DEFAULT, gate.current())
    }

    @Test
    fun `mirrors a stored choice`() = runTest {
        val preferences = UserPreferencesDataSource(InMemoryPreferencesDataStore())
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        val gate = YouTubeSourceGate(preferences, backgroundScope)
        runCurrent()

        assertEquals(YouTubeSource.OFFICIAL, gate.current())
    }

    @Test
    fun `follows a change of choice`() = runTest {
        val preferences = UserPreferencesDataSource(InMemoryPreferencesDataStore())
        val gate = YouTubeSourceGate(preferences, backgroundScope)
        runCurrent()
        assertEquals(YouTubeSource.EXTRACTOR, gate.current())

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        runCurrent()

        assertEquals(YouTubeSource.OFFICIAL, gate.current())
    }
}

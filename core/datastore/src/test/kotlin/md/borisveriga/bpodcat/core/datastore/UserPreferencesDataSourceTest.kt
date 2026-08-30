package md.borisveriga.bpodcat.core.datastore

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.model.DownloadSettings
import md.borisveriga.bpodcat.core.model.LibraryLayout
import md.borisveriga.bpodcat.core.model.PlaybackSettings
import md.borisveriga.bpodcat.core.testing.InMemoryPreferencesDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [UserPreferencesDataSource].
 *
 * Backed by [InMemoryPreferencesDataStore] rather than a file; see that class for why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesDataSourceTest {

    private lateinit var store: InMemoryPreferencesDataStore
    private lateinit var dataSource: UserPreferencesDataSource

    @Before
    fun setUp() {
        store = InMemoryPreferencesDataStore()
        dataSource = UserPreferencesDataSource(store)
    }

    @Test
    fun `an untouched store reports the documented defaults`() = runTest {
        val settings = dataSource.playbackSettings.first()

        assertEquals(PlaybackSettings(), settings)
        assertNull(dataSource.lastPlayedEpisodeId.first())
    }

    @Test
    fun `speed round trips`() = runTest {
        dataSource.setSpeed(1.5f)

        assertEquals(1.5f, dataSource.playbackSettings.first().speed, 0.001f)
    }

    @Test
    fun `an out of range speed is clamped rather than rejected`() = runTest {
        dataSource.setSpeed(12f)
        assertEquals(PlaybackSettings.SPEED_RANGE.endInclusive, dataSource.playbackSettings.first().speed, 0.001f)

        dataSource.setSpeed(0f)
        assertEquals(PlaybackSettings.SPEED_RANGE.start, dataSource.playbackSettings.first().speed, 0.001f)
    }

    @Test
    fun `skip intervals round trip and reject sub-second values`() = runTest {
        dataSource.setSkipIntervals(forwardMs = 45_000L, backMs = 15L)

        val settings = dataSource.playbackSettings.first()
        assertEquals(45_000L, settings.skipForwardMs)
        assertEquals(1_000L, settings.skipBackMs)
    }

    @Test
    fun `the last played episode can be set and cleared`() = runTest {
        dataSource.setLastPlayedEpisodeId("episode-1")
        assertEquals("episode-1", dataSource.lastPlayedEpisodeId.first())

        dataSource.setLastPlayedEpisodeId(null)
        assertNull(dataSource.lastPlayedEpisodeId.first())
    }

    @Test
    fun `auto play next round trips`() = runTest {
        dataSource.setAutoPlayNext(false)

        assertFalse(dataSource.playbackSettings.first().autoPlayNext)
    }

    @Test
    fun `an untouched store reports the documented download defaults`() = runTest {
        val settings = dataSource.downloadSettings.first()

        assertEquals(DownloadSettings(), settings)
        // Spelled out as well as compared, because "off" and "Wi-Fi only" are the two defaults that
        // stop the app spending a user's mobile data without being asked.
        assertFalse(settings.autoDownloadNewEpisodes)
        assertTrue(settings.unmeteredOnly)
    }

    @Test
    fun `download settings round trip`() = runTest {
        dataSource.setAutoDownloadNewEpisodes(true)
        dataSource.setUnmeteredOnly(false)
        dataSource.setKeepLimitPerPodcast(5)
        dataSource.setDeleteAfterPlaying(false)

        assertEquals(
            DownloadSettings(
                autoDownloadNewEpisodes = true,
                unmeteredOnly = false,
                keepLimitPerPodcast = 5,
                deleteAfterPlaying = false,
            ),
            dataSource.downloadSettings.first(),
        )
    }

    @Test
    fun `a negative keep limit is stored as keep-all rather than deleting everything`() = runTest {
        dataSource.setKeepLimitPerPodcast(-3)

        val settings = dataSource.downloadSettings.first()
        assertEquals(DownloadSettings.KEEP_ALL, settings.keepLimitPerPodcast)
        assertFalse(settings.enforcesKeepLimit)
    }

    @Test
    fun `the library layout defaults to the grid and round trips`() = runTest {
        assertEquals(LibraryLayout.DEFAULT, dataSource.libraryLayout.first())

        dataSource.setLibraryLayout(LibraryLayout.LIST)

        assertEquals(LibraryLayout.LIST, dataSource.libraryLayout.first())
    }

    @Test
    fun `a layout this build does not know falls back rather than throwing`() = runTest {
        // What a downgrade looks like: the file was written by a build that offered a third
        // layout. Reading it must not take the library screen down.
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(stringPreferencesKey("library_layout"), "CAROUSEL")
            }
        }

        assertEquals(LibraryLayout.DEFAULT, dataSource.libraryLayout.first())
    }
}

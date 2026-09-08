package md.borisveriga.megapodcastplayer.core.datastore

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import md.borisveriga.megapodcastplayer.core.model.EpisodeSort
import md.borisveriga.megapodcastplayer.core.model.LibraryLayout
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
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
    fun `the downloads order is empty until something is dragged`() = runTest {
        assertEquals(emptyList<String>(), dataSource.downloadOrder.first())
    }

    @Test
    fun `the downloads order round trips, keeping the order it was given`() = runTest {
        dataSource.setDownloadOrder(listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), dataSource.downloadOrder.first())
    }

    @Test
    fun `clearing the downloads order reads back as no order at all`() = runTest {
        dataSource.setDownloadOrder(listOf("a"))
        dataSource.setDownloadOrder(emptyList())

        // An empty list is stored as an empty string, which splits into one empty id: without the
        // filter the screen would think it had an arrangement and sort every row behind it.
        assertEquals(emptyList<String>(), dataSource.downloadOrder.first())
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
    fun `a show with no stored settings reads as the defaults`() = runTest {
        assertEquals(emptyMap<String, ShowSettings>(), dataSource.showSettings.first())
    }

    @Test
    fun `one show's settings do not disturb another's`() = runTest {
        dataSource.updateShowSettings("show-1") { it.copy(episodeSort = EpisodeSort.OLDEST_FIRST) }
        dataSource.updateShowSettings("show-2") {
            it.copy(episodeFilter = EpisodeFilter.DOWNLOADED)
        }

        val stored = dataSource.showSettings.first()

        assertEquals(EpisodeSort.OLDEST_FIRST, stored.getValue("show-1").episodeSort)
        assertEquals(EpisodeFilter.ALL, stored.getValue("show-1").episodeFilter)
        assertEquals(EpisodeFilter.DOWNLOADED, stored.getValue("show-2").episodeFilter)
    }

    @Test
    fun `a show put back to its defaults stops being stored`() = runTest {
        dataSource.updateShowSettings("show-1") { it.copy(episodeSort = EpisodeSort.OLDEST_FIRST) }
        assertTrue(dataSource.showSettings.first().containsKey("show-1"))

        dataSource.updateShowSettings("show-1") { it.copy(episodeSort = EpisodeSort.NEWEST_FIRST) }

        // Otherwise the map grows a row for every show the user has ever glanced at with a
        // filter on, and never loses one.
        assertFalse(dataSource.showSettings.first().containsKey("show-1"))
    }
}

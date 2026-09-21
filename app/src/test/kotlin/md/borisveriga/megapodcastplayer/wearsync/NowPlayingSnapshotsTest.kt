package md.borisveriga.megapodcastplayer.wearsync

import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the phone-to-watch state mapping. */
class NowPlayingSnapshotsTest {

    private val settings = PlaybackSettings(skipForwardMs = 45_000L, skipBackMs = 15_000L)

    private val queue = listOf(
        playable("ep-1", "One"),
        playable("ep-2", "Two"),
        playable("ep-3", "Three"),
    )

    @Test
    fun `up next starts after the episode that is playing`() {
        val snapshot = nowPlayingSnapshot(
            playback = PlaybackState(episodeId = "ep-2"),
            settings = settings,
            queue = queue,
            downloads = emptyList(),
            publishedAtMs = 0L,
        )

        assertEquals(listOf("ep-3"), snapshot.upNext.map { it.id })
    }

    @Test
    fun `up next is the whole queue when nothing is playing`() {
        val snapshot = nowPlayingSnapshot(
            playback = PlaybackState(episodeId = null),
            settings = settings,
            queue = queue,
            downloads = emptyList(),
            publishedAtMs = 0L,
        )

        assertEquals(listOf("ep-1", "ep-2", "ep-3"), snapshot.upNext.map { it.id })
        assertTrue(snapshot.isIdle)
    }

    @Test
    fun `an episode playing that is not in the durable queue still lists the queue`() {
        val snapshot = nowPlayingSnapshot(
            // Playing straight from a show, without queueing first.
            playback = PlaybackState(episodeId = "ep-99"),
            settings = settings,
            queue = queue,
            downloads = emptyList(),
            publishedAtMs = 0L,
        )

        assertEquals(listOf("ep-1", "ep-2", "ep-3"), snapshot.upNext.map { it.id })
    }

    @Test
    fun `the queue sent to the watch is capped`() {
        val longQueue = (1..50).map { playable("ep-$it", "Episode $it") }

        val snapshot = nowPlayingSnapshot(
            playback = PlaybackState(episodeId = null),
            settings = settings,
            queue = longQueue,
            downloads = emptyList(),
            publishedAtMs = 0L,
        )

        assertEquals(20, snapshot.upNext.size)
    }

    @Test
    fun `the skip intervals sent are the phone's preferences`() {
        val snapshot = nowPlayingSnapshot(PlaybackState(), settings, queue, emptyList(), 0L)

        assertEquals(45_000L, snapshot.skipForwardMs)
        assertEquals(15_000L, snapshot.skipBackMs)
    }

    @Test
    fun `previous is offered only when something precedes the current episode`() {
        val first = nowPlayingSnapshot(
            playback = PlaybackState(episodeId = "ep-1", queueIndex = 0),
            settings = settings,
            queue = queue,
            downloads = emptyList(),
            publishedAtMs = 0L,
        )
        val second = nowPlayingSnapshot(
            playback = PlaybackState(episodeId = "ep-2", queueIndex = 1),
            settings = settings,
            queue = queue,
            downloads = emptyList(),
            publishedAtMs = 0L,
        )

        assertFalse(first.hasPrevious)
        assertTrue(second.hasPrevious)
    }

    @Test
    fun `player fields are carried across verbatim`() {
        val playback = PlaybackState(
            episodeId = "ep-2",
            title = "Two",
            showTitle = "The Show",
            artworkUrl = "https://example.com/two.jpg",
            isPlaying = true,
            isBuffering = false,
            positionMs = 5_000L,
            durationMs = 60_000L,
            speed = 1.5f,
            queueEpisodeIds = listOf("ep-2", "ep-3"),
            queueIndex = 0,
        )

        val snapshot = nowPlayingSnapshot(playback, settings, queue, emptyList(), publishedAtMs = 77L)

        assertEquals("ep-2", snapshot.episodeId)
        assertEquals("Two", snapshot.title)
        assertEquals("The Show", snapshot.showTitle)
        assertTrue(snapshot.isPlaying)
        assertEquals(5_000L, snapshot.positionMs)
        assertEquals(60_000L, snapshot.durationMs)
        assertEquals(1.5f, snapshot.speed, 0f)
        assertTrue(snapshot.hasNext)
        assertEquals(77L, snapshot.publishedAtMs)
    }

    /**
     * The downloaded list is what the wrist can act on that the queue does not already cover.
     * Everything in the queue is left out of it — otherwise the same episode would appear twice on
     * one small screen, once to play and once to queue, and the second offer would do nothing.
     */
    @Test
    fun `downloads already queued are left out of the downloaded list`() {
        val snapshot = nowPlayingSnapshot(
            playback = PlaybackState(episodeId = "ep-1"),
            settings = settings,
            queue = queue,
            downloads = listOf(downloaded("ep-2"), downloaded("ep-7")),
            publishedAtMs = 0L,
        )

        assertEquals(listOf("ep-7"), snapshot.downloaded.map { it.id })
    }

    /** A transfer that has not finished is not something a wrist can play, so it is not offered. */
    @Test
    fun `only finished downloads reach the watch`() {
        val snapshot = nowPlayingSnapshot(
            playback = PlaybackState(),
            settings = settings,
            queue = emptyList(),
            downloads = listOf(
                downloaded("done"),
                downloaded("running", DownloadState.DOWNLOADING),
                downloaded("waiting", DownloadState.QUEUED),
                downloaded("broken", DownloadState.FAILED),
            ),
            publishedAtMs = 0L,
        )

        assertEquals(listOf("done"), snapshot.downloaded.map { it.id })
    }

    @Test
    fun `the downloaded list sent to the watch is capped`() {
        val many = (1..50).map { downloaded("dl-$it") }

        val snapshot = nowPlayingSnapshot(
            playback = PlaybackState(),
            settings = settings,
            queue = emptyList(),
            downloads = many,
            publishedAtMs = 0L,
        )

        assertEquals(20, snapshot.downloaded.size)
    }

    @Test
    fun `a downloaded episode carries the show it belongs to`() {
        val snapshot = nowPlayingSnapshot(
            playback = PlaybackState(),
            settings = settings,
            queue = emptyList(),
            downloads = listOf(downloaded("dl-1")),
            publishedAtMs = 0L,
        )

        assertEquals("The Show", snapshot.downloaded.single().showTitle)
    }

    private fun downloaded(
        id: String,
        state: DownloadState = DownloadState.COMPLETED,
    ) = EpisodeWithShow(
        episode = episode(id, "Downloaded $id").copy(downloadState = state),
        showTitle = "The Show",
        showArtworkUrl = null,
    )

    private fun playable(id: String, title: String) = PlayableEpisode(
        episode = episode(id, title),
        showTitle = "The Show",
        showArtworkUrl = null,
    )

    private fun episode(id: String, title: String) = Episode(
        id = id,
        podcastId = "show-1",
        guid = id,
        title = title,
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = null,
        durationMs = null,
        publishedAt = null,
        sizeBytes = null,
    )
}

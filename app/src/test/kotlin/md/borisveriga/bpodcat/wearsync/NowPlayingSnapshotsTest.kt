package md.borisveriga.bpodcat.wearsync

import md.borisveriga.bpodcat.core.media.PlayableEpisode
import md.borisveriga.bpodcat.core.media.PlaybackState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.PlaybackSettings
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
            publishedAtMs = 0L,
        )

        assertEquals(20, snapshot.upNext.size)
    }

    @Test
    fun `the skip intervals sent are the phone's preferences`() {
        val snapshot = nowPlayingSnapshot(PlaybackState(), settings, queue, 0L)

        assertEquals(45_000L, snapshot.skipForwardMs)
        assertEquals(15_000L, snapshot.skipBackMs)
    }

    @Test
    fun `previous is offered only when something precedes the current episode`() {
        val first = nowPlayingSnapshot(
            playback = PlaybackState(episodeId = "ep-1", queueIndex = 0),
            settings = settings,
            queue = queue,
            publishedAtMs = 0L,
        )
        val second = nowPlayingSnapshot(
            playback = PlaybackState(episodeId = "ep-2", queueIndex = 1),
            settings = settings,
            queue = queue,
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

        val snapshot = nowPlayingSnapshot(playback, settings, queue, publishedAtMs = 77L)

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

    private fun playable(id: String, title: String) = PlayableEpisode(
        episode = Episode(
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
        ),
        showTitle = "The Show",
        showArtworkUrl = null,
    )
}

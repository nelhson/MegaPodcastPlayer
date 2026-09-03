package md.borisveriga.megapodcastplayer.wearsync

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [shouldPublish], the rule that keeps the Bluetooth radio quiet.
 *
 * Everything else in [NowPlayingPublisher] is plumbing onto Play Services; this is the part with a
 * decision in it, and the part that would drain a battery if it were wrong.
 */
class NowPlayingPublisherTest {

    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "Episode one",
        isPlaying = true,
        positionMs = 10_000L,
        durationMs = 600_000L,
        speed = 1f,
    )

    @Test
    fun `the first snapshot is always published`() {
        assertTrue(shouldPublish(previous = null, candidate = playing, nowMs = 0L))
    }

    @Test
    fun `ordinary progress is not published`() {
        val previous = PublishedSnapshot(playing, atMs = 1_000L)
        // Five seconds later, five seconds further in: exactly what the watch already assumes.
        val candidate = playing.copy(positionMs = 15_000L, publishedAtMs = 6_000L)

        assertFalse(shouldPublish(previous, candidate, nowMs = 6_000L))
    }

    @Test
    fun `progress at a faster speed is still not published`() {
        val fast = playing.copy(speed = 2f)
        val previous = PublishedSnapshot(fast, atMs = 1_000L)
        val candidate = fast.copy(positionMs = 20_000L)

        assertFalse(shouldPublish(previous, candidate, nowMs = 6_000L))
    }

    @Test
    fun `a seek is published`() {
        val previous = PublishedSnapshot(playing, atMs = 1_000L)
        val candidate = playing.copy(positionMs = 300_000L)

        assertTrue(shouldPublish(previous, candidate, nowMs = 6_000L))
    }

    @Test
    fun `a seek backwards is published`() {
        val previous = PublishedSnapshot(playing, atMs = 1_000L)
        val candidate = playing.copy(positionMs = 0L)

        assertTrue(shouldPublish(previous, candidate, nowMs = 6_000L))
    }

    @Test
    fun `pausing is published`() {
        val previous = PublishedSnapshot(playing, atMs = 1_000L)
        val candidate = playing.copy(isPlaying = false, positionMs = 15_000L)

        assertTrue(shouldPublish(previous, candidate, nowMs = 6_000L))
    }

    @Test
    fun `a paused player that has not moved is not republished`() {
        val paused = playing.copy(isPlaying = false)
        val previous = PublishedSnapshot(paused, atMs = 1_000L)

        assertFalse(shouldPublish(previous, paused.copy(publishedAtMs = 60_000L), nowMs = 60_000L))
    }

    @Test
    fun `changing episode is published`() {
        val previous = PublishedSnapshot(playing, atMs = 1_000L)
        val candidate = playing.copy(episodeId = "ep-2", title = "Episode two", positionMs = 15_000L)

        assertTrue(shouldPublish(previous, candidate, nowMs = 6_000L))
    }

    @Test
    fun `changing the queue is published`() {
        val previous = PublishedSnapshot(playing, atMs = 1_000L)
        val candidate = playing.copy(hasNext = true, positionMs = 15_000L)

        assertTrue(shouldPublish(previous, candidate, nowMs = 6_000L))
    }

    @Test
    fun `changing speed is published`() {
        val previous = PublishedSnapshot(playing, atMs = 1_000L)
        val candidate = playing.copy(speed = 2f, positionMs = 15_000L)

        assertTrue(shouldPublish(previous, candidate, nowMs = 6_000L))
    }
}

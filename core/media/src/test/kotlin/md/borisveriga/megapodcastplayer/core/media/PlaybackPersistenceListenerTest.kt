package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PlaybackPersistenceListener] and [positionReading].
 *
 * The listener is where "what the player did" becomes "what the database remembers", and every
 * rule in it is a judgement call that a refactor could quietly flip: a seek must not mark an
 * episode played, a source refresh must not rewrite the queue, and a pause must flush the position
 * immediately. Each test pins one such rule against a stubbed [Player].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackPersistenceListenerTest {

    private val player: Player = mockk(relaxed = true)
    private val recorder = RecordingProgressRecorder()
    private val userPreferences: UserPreferencesDataSource = mockk(relaxed = true)

    /** Unconfined, so a launched write has completed by the time the callback returns. */
    private val listener = PlaybackPersistenceListener(
        player = player,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        progressRecorder = recorder,
        userPreferences = userPreferences,
    )

    @Test
    fun `an automatic transition marks the previous episode played`() {
        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        assertEquals(listOf("ep-1"), recorder.completed)
    }

    @Test
    fun `a seek marks nothing played`() {
        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-1"),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        assertTrue(recorder.completed.isEmpty())
    }

    @Test
    fun `the user skipping ahead marks nothing played`() {
        // "Next" arrives as a seek too: Media3 reports a user-initiated item change as
        // DISCONTINUITY_REASON_SEEK, not as an automatic transition.
        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        assertTrue(recorder.completed.isEmpty())
    }

    @Test
    fun `the last episode ending marks it played`() {
        every { player.currentMediaItem } returns mediaItem("ep-3")

        listener.onPlaybackStateChanged(Player.STATE_ENDED)

        assertEquals(listOf("ep-3"), recorder.completed)
    }

    @Test
    fun `becoming ready marks nothing played`() {
        every { player.currentMediaItem } returns mediaItem("ep-3")

        listener.onPlaybackStateChanged(Player.STATE_READY)

        assertTrue(recorder.completed.isEmpty())
    }

    @Test
    fun `pausing flushes the position with the measured duration`() {
        loaded(episodeId = "ep-1", positionMs = 12_000L, durationMs = 90_000L)

        listener.onIsPlayingChanged(false)

        assertEquals(listOf(Position("ep-1", 12_000L, 90_000L)), recorder.positions)
    }

    @Test
    fun `starting to play writes nothing`() {
        loaded(episodeId = "ep-1", positionMs = 12_000L, durationMs = 90_000L)

        listener.onIsPlayingChanged(true)

        assertTrue(recorder.positions.isEmpty())
    }

    @Test
    fun `pausing with nothing loaded writes nothing`() {
        every { player.currentMediaItem } returns null

        listener.onIsPlayingChanged(false)

        assertTrue(recorder.positions.isEmpty())
    }

    @Test
    fun `a playlist change mirrors the queue in order`() {
        every { player.mediaItemCount } returns 2
        every { player.getMediaItemAt(0) } returns mediaItem("ep-1")
        every { player.getMediaItemAt(1) } returns mediaItem("ep-2")

        listener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)

        assertEquals(listOf(listOf("ep-1", "ep-2")), recorder.queues)
    }

    @Test
    fun `a source update does not rewrite the queue`() {
        // The timeline also changes when a stream's duration becomes known; that is not an edit.
        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } returns mediaItem("ep-1")

        listener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)

        assertTrue(recorder.queues.isEmpty())
    }

    @Test
    fun `a media item transition records the last played episode`() {
        listener.onMediaItemTransition(mediaItem("ep-2"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        coVerify(exactly = 1) { userPreferences.setLastPlayedEpisodeId("ep-2") }
    }

    @Test
    fun `clearing the player clears the last played episode`() {
        listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)

        coVerify(exactly = 1) { userPreferences.setLastPlayedEpisodeId(null) }
    }

    @Test
    fun `a reading with nothing loaded is null`() {
        every { player.currentMediaItem } returns null

        assertNull(player.positionReading())
    }

    @Test
    fun `an unknown duration is left null rather than passed through`() {
        // Media3 reports TIME_UNSET until the decoder has measured the stream; a recorder that
        // stored it would overwrite a perfectly good feed value with a sentinel.
        loaded(episodeId = "ep-1", positionMs = 5_000L, durationMs = C.TIME_UNSET)

        assertEquals(PositionReading("ep-1", 5_000L, null), player.positionReading())
    }

    @Test
    fun `a zero duration is treated as unknown`() {
        loaded(episodeId = "ep-1", positionMs = 5_000L, durationMs = 0L)

        assertNull(player.positionReading()?.durationMs)
    }

    /** Stubs the player as having [episodeId] loaded at [positionMs] of [durationMs]. */
    private fun loaded(episodeId: String, positionMs: Long, durationMs: Long) {
        every { player.currentMediaItem } returns mediaItem(episodeId)
        every { player.currentPosition } returns positionMs
        every { player.duration } returns durationMs
    }

    private fun mediaItem(episodeId: String): MediaItem =
        MediaItem.Builder().setMediaId(episodeId).build()

    private fun positionInfo(episodeId: String): Player.PositionInfo = Player.PositionInfo(
        /* windowUid = */ null,
        /* mediaItemIndex = */ 0,
        mediaItem(episodeId),
        /* periodUid = */ null,
        /* periodIndex = */ 0,
        /* positionMs = */ 0L,
        /* contentPositionMs = */ 0L,
        /* adGroupIndex = */ C.INDEX_UNSET,
        /* adIndexInAdGroup = */ C.INDEX_UNSET,
    )

    /** One recorded position write. */
    private data class Position(val episodeId: String, val positionMs: Long, val durationMs: Long?)

    /** A [PlaybackProgressRecorder] that keeps everything it is told, in order. */
    private class RecordingProgressRecorder : PlaybackProgressRecorder {
        val positions = mutableListOf<Position>()
        val completed = mutableListOf<String>()
        val queues = mutableListOf<List<String>>()

        override suspend fun recordPosition(episodeId: String, positionMs: Long, durationMs: Long?) {
            positions += Position(episodeId, positionMs, durationMs)
        }

        override suspend fun recordCompleted(episodeId: String) {
            completed += episodeId
        }

        override suspend fun recordQueue(episodeIds: List<String>) {
            queues += episodeIds
        }
    }
}

package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.testing.testEpisode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [StoredPositionResumeListener].
 *
 * Each test pins one side of the rule the listener encodes: which arrivals at an episode get its
 * stored position, and which were sent somewhere on purpose and must be left there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoredPositionResumeListenerTest {

    private val player: Player = mockk(relaxed = true)
    private val queueSource = StubQueueSource()
    private val crashReporter: CrashReporter = mockk(relaxed = true)

    /** Unconfined, so the launched lookup has completed by the time the callback returns. */
    private val listener = StoredPositionResumeListener(
        player = player,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        queueSource = queueSource,
        crashReporter = crashReporter,
    )

    @Test
    fun `an automatic transition onto a half-heard episode resumes it`() {
        queueSource.positions["ep-2"] = 600_000L
        loaded("ep-2", positionMs = 0L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1", positionMs = 900_000L),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        verify(exactly = 1) { player.seekTo(600_000L) }
    }

    @Test
    fun `next onto a half-heard episode resumes it`() {
        // "Next" arrives as a seek that changed episode and landed at zero.
        queueSource.positions["ep-2"] = 600_000L
        loaded("ep-2", positionMs = 0L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1", positionMs = 47_000L),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        verify(exactly = 1) { player.seekTo(600_000L) }
    }

    @Test
    fun `an episode sent to a position is left there`() {
        // What playNow does: seekTo(index, positionMs). The position was chosen; it is not the
        // default start, and the database is not even consulted.
        queueSource.positions["ep-2"] = 600_000L
        loaded("ep-2", positionMs = 30_000L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2", positionMs = 30_000L),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `a seek within the episode is not an arrival`() {
        queueSource.positions["ep-1"] = 600_000L
        loaded("ep-1", positionMs = 0L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1", positionMs = 12_000L),
            positionInfo("ep-1", positionMs = 0L),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `an episode never started stays at the start`() {
        queueSource.positions["ep-2"] = 0L
        loaded("ep-2", positionMs = 0L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `a player that moved on while the database answered is left alone`() {
        queueSource.positions["ep-2"] = 600_000L
        loaded("ep-3", positionMs = 0L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `a player already past the stored position is left alone`() {
        queueSource.positions["ep-2"] = 600_000L
        loaded("ep-2", positionMs = 700_000L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `an episode the database no longer holds is left alone`() {
        loaded("ep-2", positionMs = 0L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `a failed lookup is reported, not thrown`() {
        queueSource.failure = IllegalStateException("database closed")
        loaded("ep-2", positionMs = 0L)

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        verify(exactly = 1) { crashReporter.recordNonFatal(any(), any<IllegalStateException>()) }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    /** Stubs the player as having [episodeId] loaded at [positionMs]. */
    private fun loaded(episodeId: String, positionMs: Long) {
        every { player.currentMediaItem } returns mediaItem(episodeId)
        every { player.currentPosition } returns positionMs
    }

    private fun mediaItem(episodeId: String): MediaItem =
        MediaItem.Builder().setMediaId(episodeId).build()

    private fun positionInfo(
        episodeId: String,
        positionMs: Long = 0L,
    ): Player.PositionInfo = Player.PositionInfo(
        /* windowUid = */ null,
        /* mediaItemIndex = */ 0,
        mediaItem(episodeId),
        /* periodUid = */ null,
        /* periodIndex = */ 0,
        /* positionMs = */ positionMs,
        /* contentPositionMs = */ positionMs,
        /* adGroupIndex = */ C.INDEX_UNSET,
        /* adIndexInAdGroup = */ C.INDEX_UNSET,
    )

    /**
     * A [PlaybackQueueSource] holding a stored position per episode id; an id it has no entry for
     * is an episode the database no longer holds.
     */
    private class StubQueueSource : PlaybackQueueSource {
        val positions = mutableMapOf<String, Long>()
        var failure: Throwable? = null

        override suspend fun resumePoint(): ResumePoint = ResumePoint.EMPTY

        override suspend fun playableEpisodes(episodeIds: List<String>): List<PlayableEpisode> {
            failure?.let { throw it }
            return episodeIds.mapNotNull { id ->
                positions[id]?.let { positionMs ->
                    PlayableEpisode(
                        episode = testEpisode(id = id, positionMs = positionMs),
                        showTitle = "Show",
                        showArtworkUrl = null,
                    )
                }
            }
        }
    }
}

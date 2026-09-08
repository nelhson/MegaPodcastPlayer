package md.borisveriga.megapodcastplayer.core.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * One seek that landed inside the loaded episode.
 *
 * @property mediaItemIndex which episode it landed in. Recorded because it is half the assertion:
 *   a chapter seek has to stay in the episode that was already playing.
 * @property positionMs where it seeked to.
 */
internal data class Seek(val mediaItemIndex: Int, val positionMs: Long)

/**
 * A player that records what was asked of it.
 *
 * A [SimpleBasePlayer] rather than a mock, because [ChapterAwarePlayer] is a
 * [androidx.media3.common.ForwardingSimpleBasePlayer] and what it does with a command depends on
 * what the wrapped player reports it can do — a mock would let the assertions pass against a player
 * that could not exist. This one reports a plain seekable playlist with no live content, which is
 * every podcast episode.
 *
 * @param itemCount how many episodes are queued.
 * @param mediaItemIndex which one is loaded.
 * @param positionMs how far into it the playhead is.
 */
internal class FakePlayer(
    private val itemCount: Int,
    private var mediaItemIndex: Int = 0,
    private var positionMs: Long = 0L,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** Seeks that stayed in the loaded episode, in the order they arrived. */
    val contentSeeks = mutableListOf<Seek>()

    /** Indices of episodes seeked to, in the order they arrived. */
    val mediaItemSeeks = mutableListOf<Int>()

    /**
     * Advances the playhead the way playback would, with no seek involved.
     *
     * @param positionMs the new position.
     */
    fun moveTo(positionMs: Long) {
        this.positionMs = positionMs
        invalidateState()
    }

    /**
     * Loads another queued episode the way an automatic transition would.
     *
     * @param index which episode; the playhead goes back to its start, as it would.
     */
    fun loadItem(index: Int) {
        mediaItemIndex = index
        positionMs = 0L
        invalidateState()
    }

    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                )
                // The two the wrapper is about, offered as a real player offers them: next only
                // when an episode follows, previous always, an episode being restartable.
                .addIf(Player.COMMAND_SEEK_TO_NEXT, mediaItemIndex < itemCount - 1)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build(),
        )
        .setPlaylist(
            List(itemCount) { index ->
                MediaItemData.Builder(/* uid = */ "uid-$index")
                    .setMediaItem(MediaItem.Builder().setMediaId("ep-$index").build())
                    .setIsSeekable(true)
                    .setDurationUs(EPISODE_DURATION_US)
                    .build()
            },
        )
        .setCurrentMediaItemIndex(mediaItemIndex)
        .setContentPositionMs(positionMs)
        .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != this.mediaItemIndex) {
            mediaItemSeeks += mediaItemIndex
            this.mediaItemIndex = mediaItemIndex
            this.positionMs = 0L
        } else if (positionMs != C.TIME_UNSET) {
            contentSeeks += Seek(mediaItemIndex, positionMs)
            this.positionMs = positionMs
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        /** Twenty minutes, long enough to contain every chapter the tests use. */
        const val EPISODE_DURATION_US = 20L * 60L * 1_000_000L
    }
}

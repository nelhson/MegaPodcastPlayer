package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import md.borisveriga.megapodcastplayer.core.model.chapters.nextStartAfter
import md.borisveriga.megapodcastplayer.core.model.chapters.previousStartBefore

/**
 * The player the media session exposes: an [androidx.media3.exoplayer.ExoPlayer] whose
 * *previous* and *next* mean chapters whenever the episode playing has any.
 *
 * The app's own transport row has meant that since chapters landed — an episode with chapters is a
 * list of segments, and *next* is the next segment. Everywhere else it still meant the next
 * *episode*: the notification, the lock screen, a car's steering-wheel buttons, a headset, the
 * Assistant. Those all arrive as `COMMAND_SEEK_TO_NEXT` and `COMMAND_SEEK_TO_PREVIOUS` on whatever
 * player the session was built with, so wrapping the player is what makes one rule reach all of
 * them at once — rather than teaching each surface separately, which for the hardware ones is not
 * possible at all.
 *
 * **Why [ForwardingSimpleBasePlayer] and not `ForwardingPlayer`.** Overriding `seekToNext()` on a
 * plain forwarding player changes what the call *does* and not what the player *reports*: the
 * wrapped player still says `COMMAND_SEEK_TO_NEXT` is unavailable when a one-episode queue has
 * nothing after it, so the notification draws a disabled button that would in fact have worked, and
 * a command set that changes underneath its own `onAvailableCommandsChanged` event is exactly the
 * inconsistency Media3 documents that class as being unable to fix. [ForwardingSimpleBasePlayer]
 * rebuilds its state from the wrapped player and diffs it, so adding a command in [getState] emits
 * the event that goes with it.
 *
 * The rules are [md.borisveriga.megapodcastplayer.core.model.chapters]'s, not new ones, so the
 * notification and the in-app transport cannot drift: *next* is the next chapter's start and falls
 * through to the next episode in the last chapter; *previous* restarts the chapter being played
 * once more than three seconds into it, and steps back only when pressed near a boundary.
 *
 * Not thread-safe, and not meant to be: every entry point runs on the player's application looper,
 * which [androidx.media3.common.SimpleBasePlayer] asserts.
 *
 * @param player the real player. This wrapper owns it for the session's purposes — releasing the
 *   wrapper releases it.
 */
@UnstableApi
class ChapterAwarePlayer(player: Player) : ForwardingSimpleBasePlayer(player) {

    /** The chapters of the episode currently loaded; empty when it has none, or none yet. */
    private var chapters: List<Chapter> = emptyList()

    /**
     * Replaces the chapter list the seek commands work from.
     *
     * @param chapters the loaded episode's chapters, in start order; empty to go back to moving
     *   between episodes.
     */
    fun setChapters(chapters: List<Chapter>) {
        if (this.chapters == chapters) return
        this.chapters = chapters
        // The available commands are derived from the list, so the state that reported the old one
        // is now wrong; this is what emits `onAvailableCommandsChanged` for the difference.
        invalidateState()
    }

    /**
     * Re-derives the state, so *next* stops being offered once the last chapter is playing.
     *
     * Whether there is a chapter after the playhead is a fact about the *position*, and a position
     * advances without any event to hang an invalidation on — Media3 publishes none. So the service
     * calls this from the ticker it already runs, and the button is right within a few seconds of
     * the boundary rather than instantly. It is a no-op without chapters, which is the common case
     * and the one that must not pay for this.
     */
    fun refreshChapterCommands() {
        if (chapters.isEmpty()) return
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        if (chapters.isEmpty()) return state

        return state.buildUpon()
            .setAvailableCommands(
                state.availableCommands.buildUpon()
                    // Available exactly when it would do something: in the last chapter the command
                    // falls through to the wrapped player, which has already said whether an
                    // episode follows.
                    .addIf(Player.COMMAND_SEEK_TO_NEXT, nextChapterStart() != null)
                    // A chapter can always be restarted, so this one is unconditional. The wrapped
                    // player normally offers it anyway — an episode is seekable — but a state built
                    // from a player that did not would otherwise silently drop the command.
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build(),
            )
            .build()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val chapterStart = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT -> nextChapterStart()
            Player.COMMAND_SEEK_TO_PREVIOUS -> previousChapterStart()
            else -> null
        } ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)

        // Deliberately ignoring the resolved [mediaItemIndex]: the base class worked out which
        // *episode* the press implied, and the answer here is that it implied none.
        player.seekTo(chapterStart)
        return Futures.immediateVoidFuture()
    }

    /** Where the chapter after the playhead starts, or null in the last one (or with no list). */
    private fun nextChapterStart(): Long? = chapters.nextStartAfter(currentPositionMs())

    /** Where *previous* should land, or null when the playhead is before the first chapter. */
    private fun previousChapterStart(): Long? = chapters.previousStartBefore(currentPositionMs())

    /**
     * The wrapped player's position, or zero before it has one.
     *
     * Read from the wrapped player rather than from `this`, because [getState] calls it while the
     * wrapper's own state is mid-rebuild.
     */
    private fun currentPositionMs(): Long =
        player.currentPosition.coerceAtLeast(0L)
}

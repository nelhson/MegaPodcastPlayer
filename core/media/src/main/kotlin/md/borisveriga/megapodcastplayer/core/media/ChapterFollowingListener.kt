package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps [ChapterAwarePlayer]'s chapter list pointed at the episode that is actually loaded.
 *
 * A third listener beside [PlaybackPersistenceListener] and [EndOfEpisodeBellListener], installed
 * on the same player and split out for the same reason: one callback, one rule, testable against a
 * stub. The rule is that a chapter list belongs to exactly one episode, so the moment the loaded
 * item changes the old list stops being true — and it stops being true *before* the new one can be
 * resolved, because resolving may have to reach the network.
 *
 * That ordering is the whole of this class. The list is cleared synchronously on the transition and
 * the resolution is launched after it, so the window in between is one where *next* means the next
 * episode rather than a chapter of the episode before. Leaving the old list in place for those
 * milliseconds would make the notification's buttons seek into the wrong episode, which is worse
 * than briefly not knowing about chapters at all.
 *
 * @property player the wrapper being kept up to date.
 * @property scope where resolution runs; the service cancels it when the player is released.
 * @property chapterSource where the chapters come from.
 */
@UnstableApi
internal class ChapterFollowingListener(
    private val player: ChapterAwarePlayer,
    private val scope: CoroutineScope,
    private val chapterSource: PlaybackChapterSource,
) : Player.Listener {

    /** The resolution in flight, cancelled when the episode it was for is no longer loaded. */
    private var resolution: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        resolution?.cancel()
        player.setChapters(emptyList())

        val episodeId = mediaItem?.episodeId ?: return
        resolution = scope.launch {
            val chapters = chapterSource.chaptersFor(episodeId)
            // The episode may have changed again while the fetch was out; the cancellation above
            // handles the ordinary case, and this handles the one where it lost the race.
            if (player.currentMediaItem?.episodeId == episodeId) {
                player.setChapters(chapters)
            }
        }
    }
}

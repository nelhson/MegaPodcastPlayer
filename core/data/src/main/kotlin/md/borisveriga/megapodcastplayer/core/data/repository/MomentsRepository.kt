package md.borisveriga.megapodcastplayer.core.data.repository

import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode

/**
 * The timestamped bookmarks the user saves while listening.
 *
 * A moment is the one thing in this app the user *authors*. Everything else here — shows,
 * episodes, positions — is either fetched or re-derivable, and a wiped database costs the user only
 * the time it takes to restore a backup. A note typed at 12:23 of an episode is not recoverable
 * from anywhere, which is why this interface has an export on it rather than leaving that to the
 * backup: the backup restores MegaPodcastPlayer, and the export survives it.
 */
interface MomentsRepository {

    /** Observes every saved moment, newest first, with what a row and a share both need. */
    fun observeMoments(): Flow<List<MomentWithEpisode>>

    /**
     * Observes how many moments an episode has, so a player can say whether marking did anything.
     *
     * @param episodeId the episode to count, or null when nothing is playing.
     */
    fun observeCountForEpisode(episodeId: String?): Flow<Int>

    /**
     * Observes one episode's moments, earliest first.
     *
     * What the player's moment count opens onto. The count alone was evidence that the button
     * worked and nothing more: the marks themselves could only be read on another screen, which is
     * a long way to go to jump back twelve minutes.
     *
     * @param episodeId the episode, or null while nothing is loaded, which emits an empty list.
     */
    fun observeForEpisode(episodeId: String?): Flow<List<Moment>>

    /**
     * Marks a spot in an episode.
     *
     * Marking the same spot twice does not make two moments: a mark within
     * [md.borisveriga.megapodcastplayer.core.model.MOMENT_MERGE_WINDOW_MS] of an existing one is
     * that one. The button is pressed with a phone in a pocket or a watch on a wrist, and the
     * second press is nearly always the user's doubt about the first rather than a second thought.
     *
     * @param episodeId the episode being listened to.
     * @param positionMs where in it to mark.
     * @param note an initial note, when the caller already has one.
     * @return the moment now standing at that spot — the new one, or the existing one a repeat
     *   folded into — or null when it could not be saved, which means the episode is not in the
     *   database. The moment rather than its id, so a caller confirming the save can name the
     *   position that was actually kept rather than the one the user pressed at.
     */
    suspend fun mark(episodeId: String, positionMs: Long, note: String? = null): Moment?

    /**
     * Replaces a moment's note.
     *
     * @param id the moment.
     * @param note the new note; blank is stored as no note at all, so an emptied field reads back
     *   the way it looks.
     */
    suspend fun setNote(id: Long, note: String?)

    /**
     * Deletes a moment.
     *
     * @param id the moment.
     */
    suspend fun delete(id: Long)

    /**
     * Every moment as one Markdown document.
     *
     * @return the document, or an empty string when there is nothing to write.
     */
    suspend fun exportMarkdown(): String
}

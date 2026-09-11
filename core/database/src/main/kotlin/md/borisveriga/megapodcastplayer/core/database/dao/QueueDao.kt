package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeWithShowEntity
import md.borisveriga.megapodcastplayer.core.database.model.QueueEntryEntity

/**
 * Reads and writes the durable "up next" queue.
 *
 * The queue holds stored episodes only; its foreign key on `episodes` says so. The player's live
 * queue is under no such rule — an episode played from a search preview was built from a feed and
 * never stored, and one whose show was removed or rebuilt can still be sitting in the player — so
 * [enqueue] and [replaceAll] drop an id with no episode row instead of failing on it. Dropping is
 * what the read side already does with a stale id, and an entry for an unstored episode could not
 * be resumed after a process death anyway: there is no row to rebuild it from.
 */
@Dao
interface QueueDao {

    /** Observes the queue as full episode rows, in play order. */
    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN queue q ON q.episode_id = e.id
        ORDER BY q.position ASC
        """,
    )
    fun observeQueuedEpisodes(): Flow<List<EpisodeEntity>>

    /** Observes the queue joined with each episode's show, which is what the player renders. */
    @Query(
        """
        SELECT e.*, p.title AS show_title, p.artwork_url AS show_artwork_url
        FROM episodes e
        INNER JOIN queue q ON q.episode_id = e.id
        INNER JOIN podcasts p ON p.id = e.podcast_id
        ORDER BY q.position ASC
        """,
    )
    fun observeQueuedWithShow(): Flow<List<EpisodeWithShowEntity>>

    /** The queue, joined with each episode's show, read once — used to rebuild the player queue. */
    @Query(
        """
        SELECT e.*, p.title AS show_title, p.artwork_url AS show_artwork_url
        FROM episodes e
        INNER JOIN queue q ON q.episode_id = e.id
        INNER JOIN podcasts p ON p.id = e.podcast_id
        ORDER BY q.position ASC
        """,
    )
    suspend fun getQueuedWithShow(): List<EpisodeWithShowEntity>

    @Query("SELECT * FROM queue ORDER BY position ASC")
    suspend fun getEntries(): List<QueueEntryEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM queue")
    suspend fun getMaxPosition(): Int

    /**
     * Which of [episodeIds] name a stored episode — the only ones a queue entry may point at.
     *
     * @param episodeIds the ids to check.
     * @return the stored ones, in no particular order.
     */
    @Query("SELECT id FROM episodes WHERE id IN (:episodeIds)")
    suspend fun getStoredEpisodeIds(episodeIds: List<String>): List<String>

    /**
     * Writes these entries exactly as given.
     *
     * Fails on an entry for an unstored episode, which is why nothing outside this interface calls
     * it: [enqueue] and [replaceAll] filter first.
     */
    @Upsert
    suspend fun upsertAll(entries: List<QueueEntryEntity>)

    @Query("DELETE FROM queue WHERE episode_id = :episodeId")
    suspend fun remove(episodeId: String)

    @Query("DELETE FROM queue")
    suspend fun clear()

    /**
     * Appends an episode to the end of the queue, or moves nothing if it is already queued.
     *
     * Does nothing for an episode that is not stored; see the interface for why.
     */
    @Transaction
    suspend fun enqueue(episodeId: String) {
        if (getEntries().any { it.episodeId == episodeId }) return
        if (getStoredEpisodeIds(listOf(episodeId)).isEmpty()) return
        upsertAll(listOf(QueueEntryEntity(episodeId = episodeId, position = getMaxPosition() + 1)))
    }

    /**
     * Replaces the queue wholesale, which is how a drag-to-reorder is persisted.
     *
     * An id with no stored episode is left out and the rest keep their order, numbered from 0 with
     * no gap where it was; see the interface for why it is dropped rather than a failure.
     *
     * @param episodeIds the new order, first to play first.
     */
    @Transaction
    suspend fun replaceAll(episodeIds: List<String>) {
        // Read inside the transaction, so no episode can be deleted between the check and the write.
        val stored = getStoredEpisodeIds(episodeIds).toSet()
        clear()
        upsertAll(
            episodeIds
                .filter { it in stored }
                .mapIndexed { index, id -> QueueEntryEntity(episodeId = id, position = index) },
        )
    }
}

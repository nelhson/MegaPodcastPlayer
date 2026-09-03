package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeWithShowEntity
import md.borisveriga.megapodcastplayer.core.database.model.QueueEntryEntity

/** Reads and writes the durable "up next" queue. */
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

    @Upsert
    suspend fun upsertAll(entries: List<QueueEntryEntity>)

    @Query("DELETE FROM queue WHERE episode_id = :episodeId")
    suspend fun remove(episodeId: String)

    @Query("DELETE FROM queue")
    suspend fun clear()

    /** Appends an episode to the end of the queue, or moves nothing if it is already queued. */
    @Transaction
    suspend fun enqueue(episodeId: String) {
        if (getEntries().any { it.episodeId == episodeId }) return
        upsertAll(listOf(QueueEntryEntity(episodeId = episodeId, position = getMaxPosition() + 1)))
    }

    /**
     * Replaces the queue wholesale, which is how a drag-to-reorder is persisted.
     *
     * @param episodeIds the new order, first to play first.
     */
    @Transaction
    suspend fun replaceAll(episodeIds: List<String>) {
        clear()
        upsertAll(
            episodeIds.mapIndexed { index, id -> QueueEntryEntity(episodeId = id, position = index) },
        )
    }
}

package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.database.model.MomentBackupRow
import md.borisveriga.megapodcastplayer.core.database.model.MomentEntity
import md.borisveriga.megapodcastplayer.core.database.model.MomentWithEpisodeEntity

/** Reads and writes the moments the user marked while listening. */
@Dao
interface MomentDao {

    /** Observes every moment, newest first, joined with what a list row and a share both need. */
    @Query(ALL_WITH_EPISODE)
    fun observeAllWithEpisode(): Flow<List<MomentWithEpisodeEntity>>

    /**
     * Every moment, read once.
     *
     * The same projection the screen observes, because an export writes exactly what the screen
     * shows; reading it separately is only about not making a document out of a flow.
     */
    @Query(ALL_WITH_EPISODE)
    suspend fun getAllWithEpisode(): List<MomentWithEpisodeEntity>

    /**
     * Observes how many moments one episode has.
     *
     * A count rather than the rows: the player draws a number beside its mark button and nothing
     * else on that screen reads the moments themselves, so returning them would be deserialising a
     * list to call `size` on it — several times a minute, since this follows whatever is playing.
     */
    @Query("SELECT COUNT(*) FROM moments WHERE episode_id = :episodeId")
    fun observeCountForEpisode(episodeId: String): Flow<Int>

    /**
     * One episode's moments, in the order they occur in it.
     *
     * By position rather than by when they were saved, unlike the Moments screen: this list is read
     * *against* the episode, as a set of places in it, and a list that jumped backwards and forwards
     * through the same recording would be unreadable beside a scrubber.
     */
    @Query("SELECT * FROM moments WHERE episode_id = :episodeId ORDER BY position_ms ASC")
    fun observeForEpisode(episodeId: String): Flow<List<MomentEntity>>

    /**
     * The moment nearest an already-marked spot in an episode, if there is one in range.
     *
     * The unique index makes a restore idempotent, but it only catches a repeat at the *identical*
     * millisecond, which two taps of a button pressed without looking never are. This is the query
     * that catches those: the repository looks for a moment within a few seconds before saving, and
     * treats a hit as the same mark rather than a second one.
     *
     * @param episodeId the episode to look in.
     * @param fromMs earliest position to accept, inclusive.
     * @param toMs latest position to accept, inclusive.
     * @return the earliest moment in range, or null when the spot is untouched.
     */
    @Query(
        """
        SELECT * FROM moments
        WHERE episode_id = :episodeId AND position_ms BETWEEN :fromMs AND :toMs
        ORDER BY position_ms ASC
        LIMIT 1
        """,
    )
    suspend fun findNear(episodeId: String, fromMs: Long, toMs: Long): MomentEntity?

    /**
     * Saves a new moment and hands back its row id.
     *
     * `IGNORE` rather than `REPLACE`: the only thing that can collide is the unique index on
     * `(episode_id, position_ms)`, which means the mark already exists, and replacing it would
     * throw away a note the user had written on it. A skipped insert returns -1, which the caller
     * reads as "already there".
     *
     * @param moment the moment to save; its `id` is left at zero for SQLite to assign.
     * @return the new row id, or -1 when an identical mark already existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(moment: MomentEntity): Long

    /**
     * Writes the moments a backup carries, replacing any already at the same spot.
     *
     * `REPLACE` rather than `@Upsert`, and the difference matters here. Room implements an upsert
     * as "insert, and on a constraint violation update *by primary key*" — but the constraint these
     * rows violate is the unique index on `(episode_id, position_ms)`, not the primary key, and the
     * rows arrive with `id = 0` because a backup does not store local row ids. The update would
     * match nothing and the moment would be dropped in silence. `REPLACE` deletes the colliding row
     * and writes the backup's, which is what a restore means; nothing references a moment, so
     * losing the old row id costs nothing.
     *
     * @param moments the moments to write, with ids left at zero.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(moments: List<MomentEntity>)

    /** Replaces a moment's note, leaving its position and creation time alone. */
    @Query("UPDATE moments SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    /** Removes one moment. */
    @Query("DELETE FROM moments WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Every moment as the strings a backup stores.
     *
     * Resolved back to `(feed_url, guid)` for the same reason the other backup queries are: those
     * are the two strings an episode id is derived from, so a restore can rebuild the id exactly.
     */
    @Query(
        """
        SELECT p.feed_url AS feed_url, e.guid AS guid, m.position_ms AS position_ms,
               m.note AS note, m.created_at AS created_at
        FROM moments m
        INNER JOIN episodes e ON e.id = m.episode_id
        INNER JOIN podcasts p ON p.id = e.podcast_id
        ORDER BY m.created_at ASC
        """,
    )
    suspend fun getBackupRows(): List<MomentBackupRow>
}

/**
 * The joined projection, written once because two of the queries above return it.
 *
 * A constant rather than two copies of the SQL: Room verifies each `@Query` against the schema at
 * compile time, but nothing would tell us that the one-shot read and the observed one had drifted
 * into returning different columns.
 */
private const val ALL_WITH_EPISODE = """
    SELECT m.*, e.title AS episode_title, e.audio_url AS audio_url,
           p.title AS show_title, p.artwork_url AS show_artwork_url,
           p.feed_url AS feed_url
    FROM moments m
    INNER JOIN episodes e ON e.id = m.episode_id
    INNER JOIN podcasts p ON p.id = e.podcast_id
    ORDER BY m.created_at DESC
"""

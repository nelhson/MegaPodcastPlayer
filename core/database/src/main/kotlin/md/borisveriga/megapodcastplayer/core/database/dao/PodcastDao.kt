package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastWithCountsEntity

/** Reads and writes subscribed podcasts. */
@Dao
interface PodcastDao {

    /**
     * Observes the library in the user's own order, with the counts the library screen renders.
     *
     * Ordered by [PodcastEntity.sortOrder] rather than by title: the library is arranged by hand,
     * and `MIGRATION_4_5` seeded the column from the alphabetical order this used to impose, so an
     * untouched library still reads alphabetically.
     *
     * The counts are computed as correlated sub-selects rather than joins so that a show with no
     * episodes still appears (with zeroes) instead of disappearing.
     */
    @Query(
        """
        SELECT p.*,
            (SELECT COUNT(*) FROM episodes e WHERE e.podcast_id = p.id) AS episode_count,
            (SELECT COUNT(*) FROM episodes e WHERE e.podcast_id = p.id AND e.is_new = 1)
                AS new_episode_count,
            (SELECT COUNT(*) FROM episodes e WHERE e.podcast_id = p.id
                AND e.download_state = 'COMPLETED') AS downloaded_count
        FROM podcasts p
        ORDER BY p.sort_order ASC
        """,
    )
    fun observeAllWithCounts(): Flow<List<PodcastWithCountsEntity>>

    /** Observes a single podcast, emitting null once it is deleted. */
    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observeById(id: String): Flow<PodcastEntity?>

    /** Returns every podcast the periodic refresh worker should visit. */
    @Query("SELECT * FROM podcasts WHERE auto_refresh = 1")
    suspend fun getAutoRefreshable(): List<PodcastEntity>

    /** Returns every podcast, for a user-triggered "refresh all". */
    @Query("SELECT * FROM podcasts")
    suspend fun getAll(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getById(id: String): PodcastEntity?

    /** Looks a show up by feed URL — the check that stops the same feed being added twice. */
    @Query("SELECT * FROM podcasts WHERE feed_url = :feedUrl")
    suspend fun getByFeedUrl(feedUrl: String): PodcastEntity?

    @Upsert
    suspend fun upsert(podcast: PodcastEntity)

    @Delete
    suspend fun delete(podcast: PodcastEntity)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Records the outcome of a successful feed fetch.
     *
     * Kept separate from [upsert] so a refresh never rewrites title/artwork columns the user may be
     * looking at mid-scroll.
     */
    @Query(
        """
        UPDATE podcasts
        SET last_refresh_at = :refreshedAt, etag = :etag, last_modified = :lastModified
        WHERE id = :id
        """,
    )
    suspend fun updateRefreshMetadata(
        id: String,
        refreshedAt: Long,
        etag: String?,
        lastModified: String?,
    )

    @Query("UPDATE podcasts SET auto_refresh = :enabled WHERE id = :id")
    suspend fun setAutoRefresh(id: String, enabled: Boolean)

    /**
     * The position a newly subscribed show should take: the end of the library.
     *
     * `COALESCE` covers the empty library, where `MAX` is null and the first show belongs at 0.
     */
    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM podcasts")
    suspend fun nextSortOrder(): Int

    @Query("UPDATE podcasts SET sort_order = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    /**
     * Writes a whole hand-made ordering.
     *
     * A per-row `UPDATE` in one transaction rather than `QueueDao.replaceAll`'s delete-and-reinsert:
     * a podcast row carries the subscription itself — refresh metadata, artwork, the cascade its
     * episodes hang off — so deleting it to reposition it would take the show with it.
     *
     * Ids the library no longer contains are simply no-ops, which is what makes a drag that raced a
     * removal harmless.
     *
     * @param ids every show, in the order they should appear.
     */
    @Transaction
    suspend fun reorder(ids: List<String>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }
}

package md.borisveriga.bpodcat.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import md.borisveriga.bpodcat.core.database.model.EpisodeEntity
import md.borisveriga.bpodcat.core.database.model.EpisodeWithShowEntity
import md.borisveriga.bpodcat.core.model.DownloadState

/** Reads and writes episodes. */
@Dao
interface EpisodeDao {

    /** Observes one show's episodes, newest first; episodes with no date sort last. */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcast_id = :podcastId
        ORDER BY published_at IS NULL, published_at DESC
        """,
    )
    fun observeByPodcast(podcastId: String): Flow<List<EpisodeEntity>>

    /** Observes everything available offline — the "Downloaded" tab and the watch's payload. */
    @Query(
        """
        SELECT * FROM episodes
        WHERE download_state = 'COMPLETED'
        ORDER BY published_at IS NULL, published_at DESC
        """,
    )
    fun observeDownloaded(): Flow<List<EpisodeEntity>>

    /**
     * Observes everything the download stack is tracking, joined with the show each episode belongs
     * to.
     *
     * Wider than [observeDownloaded] on purpose. That one answers "what can I play offline" and
     * backs the storage figure, the keep-limit sweep and the player; this one backs the downloads
     * *screen*, where an episode still transferring, one waiting for Wi-Fi, and one that failed
     * outright are the rows that most need to be visible. A failed download the user never sees is
     * a download that silently never happened.
     *
     * Ordered by how much attention each state deserves — failures first, then what is moving, then
     * what is waiting, then the finished library — and newest-first within each group, matching
     * [observeDownloaded]. `NOT_DOWNLOADED` is every other episode in the database and is excluded.
     */
    @Query(
        """
        SELECT e.*, p.title AS show_title, p.artwork_url AS show_artwork_url
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcast_id
        WHERE e.download_state IN ('FAILED', 'DOWNLOADING', 'QUEUED', 'COMPLETED')
        ORDER BY
            CASE e.download_state
                WHEN 'FAILED' THEN 0
                WHEN 'DOWNLOADING' THEN 1
                WHEN 'QUEUED' THEN 2
                ELSE 3
            END,
            e.published_at IS NULL, e.published_at DESC
        """,
    )
    fun observeDownloadsWithShow(): Flow<List<EpisodeWithShowEntity>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observeById(id: String): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: String): EpisodeEntity?

    /**
     * Loads episodes joined with the show details the player needs, for the given ids.
     *
     * SQL cannot preserve the caller's id order, so the caller re-sorts; see
     * `DefaultPlaybackRepository`.
     */
    @Query(
        """
        SELECT e.*, p.title AS show_title, p.artwork_url AS show_artwork_url
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcast_id
        WHERE e.id IN (:ids)
        """,
    )
    suspend fun getWithShowByIds(ids: List<String>): List<EpisodeWithShowEntity>

    @Query("SELECT id FROM episodes WHERE podcast_id = :podcastId")
    suspend fun getIdsForPodcast(podcastId: String): List<String>

    /** Total bytes on disk for one show, used by the storage screen. */
    @Query(
        """
        SELECT COALESCE(SUM(downloaded_bytes), 0) FROM episodes
        WHERE podcast_id = :podcastId AND download_state = 'COMPLETED'
        """,
    )
    suspend fun getDownloadedBytes(podcastId: String): Long

    /**
     * Inserts episodes that are not already stored, leaving existing rows untouched.
     *
     * [OnConflictStrategy.IGNORE] is the whole point: a refresh must never overwrite `position_ms`,
     * `is_played` or `download_state`.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(episodes: List<EpisodeEntity>): List<Long>

    /**
     * Refreshes the publisher-owned columns of an existing episode.
     *
     * Used when a publisher fixes a typo or re-uploads audio; user state is deliberately excluded.
     * `duration_ms` is coalesced rather than overwritten so that a feed which stops publishing
     * `itunes:duration` does not erase the duration the player measured while streaming.
     */
    @Query(
        """
        UPDATE episodes SET
            title = :title,
            description = :description,
            audio_url = :audioUrl,
            artwork_url = :artworkUrl,
            duration_ms = COALESCE(:durationMs, duration_ms),
            published_at = :publishedAt,
            size_bytes = :sizeBytes
        WHERE id = :id
        """,
    )
    suspend fun updateFeedFields(
        id: String,
        title: String,
        description: String,
        audioUrl: String,
        artworkUrl: String?,
        durationMs: Long?,
        publishedAt: Long?,
        sizeBytes: Long?,
    )

    /**
     * Applies a parsed feed to the database in one transaction.
     *
     * @param episodes every episode currently in the feed, already mapped to entities with
     *   `is_new = true`.
     * @return the ids of the episodes that were genuinely new, so the caller can report
     *   "3 new episodes" without a second query.
     */
    @Transaction
    suspend fun upsertFromFeed(episodes: List<EpisodeEntity>): List<String> {
        val insertedRowIds = insertIgnoringExisting(episodes)
        val newIds = mutableListOf<String>()

        episodes.forEachIndexed { index, episode ->
            if (insertedRowIds[index] == IGNORED_ROW_ID) {
                // Already stored: only refresh the publisher's fields.
                updateFeedFields(
                    id = episode.id,
                    title = episode.title,
                    description = episode.description,
                    audioUrl = episode.audioUrl,
                    artworkUrl = episode.artworkUrl,
                    durationMs = episode.durationMs,
                    publishedAt = episode.publishedAt,
                    sizeBytes = episode.sizeBytes,
                )
            } else {
                newIds += episode.id
            }
        }
        return newIds
    }

    /** Clears the "new" badge for a whole show once the user has looked at its episode list. */
    @Query("UPDATE episodes SET is_new = 0 WHERE podcast_id = :podcastId AND is_new = 1")
    suspend fun clearNewFlags(podcastId: String)

    /**
     * Persists playback progress. Called every few seconds while playing.
     *
     * Deliberately does not touch `is_played`: whether an episode counts as finished is decided by
     * the player reaching the end, not by the position crossing some threshold, and [setPlayed] is
     * the one place that decides it.
     */
    @Query("UPDATE episodes SET position_ms = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: String, positionMs: Long)

    /**
     * Fills in a duration the feed never published, using the one the decoder measured.
     *
     * Scoped to rows with no duration so that a publisher's own value — which the user sees in the
     * episode list before playing — is never silently replaced.
     */
    @Query("UPDATE episodes SET duration_ms = :durationMs WHERE id = :id AND duration_ms IS NULL")
    suspend fun fillMissingDuration(id: String, durationMs: Long)

    @Query("UPDATE episodes SET is_played = :isPlayed, position_ms = :positionMs WHERE id = :id")
    suspend fun setPlayed(id: String, isPlayed: Boolean, positionMs: Long)

    /**
     * One show's downloaded episodes, newest first — the order the keep-limit sweep expects.
     *
     * Matches [observeDownloaded]'s ordering so that "the oldest downloads" means the same thing
     * everywhere.
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcast_id = :podcastId AND download_state = 'COMPLETED'
        ORDER BY published_at IS NULL, published_at DESC
        """,
    )
    suspend fun getDownloadedForPodcast(podcastId: String): List<EpisodeEntity>

    /** The download state of one episode, or null when the episode is not stored. */
    @Query("SELECT download_state FROM episodes WHERE id = :id")
    suspend fun getDownloadState(id: String): DownloadState?

    /** The ids of every episode Media3 should have a download for, used to reconcile on start-up. */
    @Query("SELECT id FROM episodes WHERE download_state != 'NOT_DOWNLOADED'")
    suspend fun getIdsWithDownloadState(): List<String>

    /**
     * Forgets every download at once, for the settings screen's "remove all".
     *
     * A single statement rather than one update per episode: Media3 reports a bulk removal without
     * enumerating what it removed, and a library of a few thousand episodes should not need a few
     * thousand writes to reflect one tap.
     */
    @Query(
        """
        UPDATE episodes
        SET download_state = 'NOT_DOWNLOADED', downloaded_bytes = 0, download_percent = 0
        WHERE download_state != 'NOT_DOWNLOADED'
        """,
    )
    suspend fun clearAllDownloadStates()

    /** Mirrors Media3's download index into the row the UI observes. */
    @Query(
        """
        UPDATE episodes SET
            download_state = :state,
            downloaded_bytes = :downloadedBytes,
            download_percent = :percent
        WHERE id = :id
        """,
    )
    suspend fun updateDownloadState(
        id: String,
        state: DownloadState,
        downloadedBytes: Long,
        percent: Float,
    )

}

/** `@Insert(IGNORE)` reports a skipped row as `-1`. */
private const val IGNORED_ROW_ID = -1L

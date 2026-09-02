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
// A DAO is a set of queries, not an object with responsibilities: the count says how many distinct
// questions the app asks of one table, and splitting it to satisfy the rule would only scatter
// those questions across files that share the same entity anyway.
@Suppress("TooManyFunctions")
@Dao
interface EpisodeDao {

    /**
     * Observes one show's episodes, newest first; episodes with no date sort last.
     *
     * Dismissed episodes are excluded. Their rows survive so a refresh recognises them and does not
     * insert them again — see [EpisodeEntity.isHidden] — but nothing is meant to draw them.
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcast_id = :podcastId AND is_hidden = 0
        ORDER BY published_at IS NULL, published_at DESC
        """,
    )
    fun observeByPodcast(podcastId: String): Flow<List<EpisodeEntity>>

    /**
     * Observes one show's episodes in the user's own order.
     *
     * Used only for shows whose `source` is `YOUTUBE`, which are the ones that can be reordered by
     * hand. [observeByPodcast] stays the query for everything else: an RSS show is a chronology,
     * and imposing a stored order on it would mean seeding and maintaining a column no user of that
     * screen can ever change.
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcast_id = :podcastId AND is_hidden = 0
        ORDER BY sort_order ASC
        """,
    )
    fun observeByPodcastOrdered(podcastId: String): Flow<List<EpisodeEntity>>

    /** Observes everything available offline — the "Downloaded" tab and the watch's payload. */
    @Query(
        """
        SELECT * FROM episodes
        WHERE download_state = 'COMPLETED' AND is_hidden = 0
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
          AND e.is_hidden = 0
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
     * @param handOrdered true for a show the user can reorder, which is what makes newly arrived
     *   episodes claim positions above everything already there. Left false for an RSS show, whose
     *   screen orders by date and never reads `sort_order`.
     * @return the ids of the episodes that were genuinely new, so the caller can report
     *   "3 new episodes" without a second query.
     */
    @Transaction
    suspend fun upsertFromFeed(
        episodes: List<EpisodeEntity>,
        handOrdered: Boolean = false,
    ): List<String> {
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

        if (handOrdered && newIds.isNotEmpty()) {
            placeNewEpisodesOnTop(episodes.first().podcastId, newIds)
        }
        return newIds
    }

    /**
     * Deletes every episode of one show.
     *
     * The queue's foreign key cascades, so entries pointing at these episodes go with them. Only
     * ever called as half of [replaceForPodcast] — on its own it would leave a subscribed show with
     * nothing in it and nothing to put back.
     */
    @Query("DELETE FROM episodes WHERE podcast_id = :podcastId")
    suspend fun deleteByPodcast(podcastId: String)

    /**
     * Replaces one show's episodes wholesale, discarding everything stored about the old ones.
     *
     * The opposite of [upsertFromFeed], which exists precisely so a refresh never touches
     * `position_ms`, `is_played` or `download_state`. This throws all three away, because it serves
     * the case where the stored list is wrong in a way no merge can correct — a publisher who
     * re-issued their back catalogue under new GUIDs, or a playlist whose stored order no longer
     * resembles the real one — and there a merge only leaves the wrong episodes sitting alongside
     * the right ones.
     *
     * One transaction, so the show is never left empty: either the old list or the new one is
     * stored, never neither.
     *
     * @param podcastId the show being rebuilt.
     * @param episodes every episode the feed now lists, already mapped to entities.
     * @param handOrdered true for a show the user can reorder, whose positions are seeded from feed
     *   order. Left false for an RSS show, whose screen orders by date and never reads `sort_order`.
     */
    @Transaction
    suspend fun replaceForPodcast(
        podcastId: String,
        episodes: List<EpisodeEntity>,
        handOrdered: Boolean = false,
    ) {
        // Every tombstone goes with the rows, which is correct: a rebuild is the user saying the
        // stored list is wrong, and a dismissal recorded against the old list has nothing to say
        // about the new one.
        deleteByPodcast(podcastId)
        // Not badged as new, for the same reason a freshly added show is not: everything arrived at
        // once, so marking the whole list unseen would say nothing about any of it.
        insertIgnoringExisting(episodes.map { it.copy(isNew = false) })
        // Feed order becomes the stored order, numbered from 0. There is nothing to preserve and
        // nothing to count down from — the hand-made order went with the rows it belonged to.
        if (handOrdered) reorder(episodes.map { it.id })
    }

    /**
     * Gives newly arrived episodes the positions above everything already stored.
     *
     * Counting *down* from the current minimum rather than shifting every existing row up: a
     * refresh must not rewrite a whole show to insert three videos, and it must not disturb an
     * order the user arranged by hand. Negative positions are the ordinary consequence and are
     * why `sort_order` is signed.
     *
     * The ids are walked in reverse so the first episode the feed listed ends up with the smallest
     * position, and therefore on top.
     *
     * @param podcastId the show the episodes belong to.
     * @param newIds the ids that were just inserted, in feed order.
     */
    @Transaction
    suspend fun placeNewEpisodesOnTop(podcastId: String, newIds: List<String>) {
        var next = minSortOrder(podcastId) - 1
        newIds.asReversed().forEach { id ->
            setSortOrder(id, next)
            next--
        }
    }

    /** The topmost position currently used by a show, or 0 for a show with no episodes yet. */
    @Query("SELECT COALESCE(MIN(sort_order), 0) FROM episodes WHERE podcast_id = :podcastId")
    suspend fun minSortOrder(podcastId: String): Int

    @Query("UPDATE episodes SET sort_order = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    /**
     * Writes a whole hand-made ordering for one show.
     *
     * Positions are rewritten from 0 up, which also normalises away the negative values a run of
     * refreshes leaves behind. Ids the show no longer contains are no-ops, so a drag that raced a
     * refresh is harmless.
     *
     * @param ids the show's episodes, in the order they should appear.
     */
    @Transaction
    suspend fun reorder(ids: List<String>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
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
     * Marks every episode of one show as played.
     *
     * `position_ms` is zeroed with it, for the same reason [setPlayed] takes a position: a finished
     * episode that still remembers where it was left would offer to resume something the user has
     * just declared done.
     *
     * One statement rather than a read followed by a write per episode: a show can hold a few
     * thousand rows, and this is one tap.
     */
    @Query("UPDATE episodes SET is_played = 1, position_ms = 0 WHERE podcast_id = :podcastId")
    suspend fun markAllPlayed(podcastId: String)

    @Query("UPDATE episodes SET is_hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("DELETE FROM queue WHERE episode_id = :episodeId")
    suspend fun deleteQueueEntry(episodeId: String)

    /**
     * Removes one episode from the show's list.
     *
     * A flag rather than a `DELETE`, because an episode id is derived from the feed's GUID: the row
     * has to stay for [insertIgnoringExisting] to recognise it and skip it, or the next refresh
     * would put the episode straight back. See [EpisodeEntity.isHidden].
     *
     * The queue entry goes explicitly. Nothing deletes the episode row, so the `ON DELETE CASCADE`
     * on `queue.episode_id` never fires, and an episode the user has just removed from a list would
     * otherwise still play its way to the front of the queue. Reaching into `queue` from here — and
     * not through `QueueDao` — is what lets both statements share one transaction.
     *
     * @param id the episode to dismiss.
     */
    @Transaction
    suspend fun hide(id: String) {
        setHidden(id, hidden = true)
        deleteQueueEntry(id)
    }

    /**
     * One show's downloaded episodes, newest first — the order the keep-limit sweep expects.
     *
     * Matches [observeDownloaded]'s ordering so that "the oldest downloads" means the same thing
     * everywhere.
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcast_id = :podcastId AND download_state = 'COMPLETED' AND is_hidden = 0
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

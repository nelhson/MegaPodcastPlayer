package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.database.model.DownloadListRowEntity
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeWithShowEntity
import md.borisveriga.megapodcastplayer.core.model.DownloadState

/** Reads and writes episodes. */
// A DAO is a set of queries, not an object with responsibilities: the count says how many distinct
// questions the app asks of one table, and splitting it to satisfy the rule would only scatter
// those questions across files that share the same entity anyway.
@Suppress("TooManyFunctions")
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
        WHERE podcast_id = :podcastId
        ORDER BY sort_order ASC
        """,
    )
    fun observeByPodcastOrdered(podcastId: String): Flow<List<EpisodeEntity>>

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

    /**
     * Observes how many downloads are still owed to the user: asked for, and not yet arrived.
     *
     * A count rather than the rows, because the one caller only needs to know when it reaches zero
     * — that is the moment a temporarily lifted "Wi-Fi only" rule can safely be put back.
     */
    @Query(
        """
        SELECT COUNT(*) FROM episodes
        WHERE download_state IN ('QUEUED', 'DOWNLOADING')
        """,
    )
    fun observeActiveDownloadCount(): Flow<Int>

    /**
     * Episodes the user has started and not finished, across every show — the widget's shelf.
     *
     * `position_ms > 0 AND is_played = 0` is the same question `Episode.isInProgress` asks, asked
     * in SQL because the alternative is reading every episode in the database into memory to filter
     * three of them out.
     *
     * Ordered by publication date rather than by when it was last played, and that is a compromise
     * worth naming: the database does not record when an episode was last *touched*, only where the
     * playhead is. Newest-first is the next best answer and matches every other list in the app.
     * The limit exists because a shelf is read across, not scrolled: someone with forty
     * half-finished episodes wants the recent ones, not all of them.
     *
     * @param limit how many to return.
     */
    @Query(
        """
        SELECT e.*, p.title AS show_title, p.artwork_url AS show_artwork_url
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcast_id
        WHERE e.position_ms > 0 AND e.is_played = 0
        ORDER BY e.published_at IS NULL, e.published_at DESC
        LIMIT :limit
        """,
    )
    fun observeInProgressWithShow(limit: Int): Flow<List<EpisodeWithShowEntity>>

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

    /**
     * One show's finished downloads, in the order they are exported as files.
     *
     * `sort_order` first, which is the playlist's own order for a YouTube show — the order the user
     * sees and may have arranged by hand. An RSS show never writes that column, so every row ties
     * on it and the publication date decides instead, oldest first: numbered files are listened to
     * from `001` up, and a feed's first episode is its oldest. Undated episodes go last.
     *
     * Only `COMPLETED`: an episode still transferring has nothing whole to export yet.
     *
     * @param podcastId the show being exported.
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcast_id = :podcastId AND download_state = 'COMPLETED'
        ORDER BY sort_order ASC, published_at IS NULL, published_at ASC
        """,
    )
    suspend fun getDownloadedForExport(podcastId: String): List<EpisodeEntity>

    /**
     * Finished downloads with the show fields the Markdown download list needs.
     *
     * Unordered: the export sorts the rows itself. The size is what is on disk when the download
     * recorded it, and otherwise the size the feed published.
     *
     * @param podcastId one show to list, or null for every show.
     */
    @Query(
        """
        SELECT p.title AS show_title, p.feed_url AS feed_url, e.title AS episode_title,
            e.audio_url AS audio_url, e.published_at AS published_at, e.duration_ms AS duration_ms,
            CASE WHEN e.downloaded_bytes > 0 THEN e.downloaded_bytes ELSE e.size_bytes END
                AS size_bytes
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcast_id
        WHERE e.download_state = 'COMPLETED' AND (:podcastId IS NULL OR e.podcast_id = :podcastId)
        """,
    )
    suspend fun getDownloadList(podcastId: String?): List<DownloadListRowEntity>

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
     * `itunes:duration` does not erase the duration the player measured while streaming. The
     * chapter columns *are* overwritten, because a publisher who re-cuts their chapters means it.
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
            size_bytes = :sizeBytes,
            chapters_url = :chaptersUrl,
            chapters_json = :chaptersJson
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
        chaptersUrl: String?,
        chaptersJson: String?,
    )

    /**
     * [updateFeedFields] for one mapped feed entry.
     *
     * @param episode the entry as the feed now publishes it; only its publisher-owned fields are
     *   written.
     */
    suspend fun refreshFeedFields(episode: EpisodeEntity) = updateFeedFields(
        id = episode.id,
        title = episode.title,
        description = episode.description,
        audioUrl = episode.audioUrl,
        artworkUrl = episode.artworkUrl,
        durationMs = episode.durationMs,
        publishedAt = episode.publishedAt,
        sizeBytes = episode.sizeBytes,
        chaptersUrl = episode.chaptersUrl,
        chaptersJson = episode.chaptersJson,
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
                refreshFeedFields(episode)
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
     * Deletes the given episodes.
     *
     * The queue's foreign key cascades, so entries pointing at these episodes go with them. Callers
     * pass at most [SQLITE_VARIABLE_CHUNK] ids at a time; see [replaceForPodcast].
     *
     * @param ids the episodes to delete.
     */
    @Query("DELETE FROM episodes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Which of the given episodes the download stack is tracking in any state.
     *
     * @param ids the episodes to check; at most [SQLITE_VARIABLE_CHUNK] of them.
     * @return the subset whose `download_state` is not `NOT_DOWNLOADED`.
     */
    @Query("SELECT id FROM episodes WHERE id IN (:ids) AND download_state != 'NOT_DOWNLOADED'")
    suspend fun getIdsWithDownloadStateIn(ids: List<String>): List<String>

    /**
     * Rebuilds one show's episode list from the feed, keeping what the user has for every episode
     * the feed still lists.
     *
     * Three things happen, in one transaction so the show is never seen half-rebuilt:
     *
     *  1. Episodes the feed no longer lists are deleted — the case no merge can express, and the
     *     reason this exists alongside [upsertFromFeed].
     *  2. Episodes it still lists keep `position_ms`, `is_played`, `is_new` and their download, and
     *     only have the publisher's fields refreshed, exactly as a refresh would.
     *  3. Episodes it lists for the first time are inserted unbadged: they arrived with a pull the
     *     user asked for, so marking them unseen would say nothing.
     *
     * For a hand-ordered show the feed's order then replaces the stored one, which is the other
     * thing a rebuild is for: a playlist whose stored order no longer resembles the real one.
     *
     * Rows are kept rather than deleted and re-inserted so that their queue entries, which cascade
     * on delete, survive too.
     *
     * @param podcastId the show being rebuilt.
     * @param episodes every episode the feed now lists, already mapped to entities.
     * @param handOrdered true for a show the user can reorder, whose positions are reseeded from
     *   feed order. Left false for an RSS show, whose screen orders by date and never reads
     *   `sort_order`.
     * @return the ids of deleted episodes that had a download in any state, so the caller can free
     *   the audio nothing points at any more.
     */
    @Transaction
    suspend fun replaceForPodcast(
        podcastId: String,
        episodes: List<EpisodeEntity>,
        handOrdered: Boolean = false,
    ): List<String> {
        val listed = episodes.mapTo(HashSet()) { it.id }
        // Chunked because SQLite caps the variables one statement may bind, and a long-running
        // playlist can withdraw more episodes than that in one go.
        val withdrawn = getIdsForPodcast(podcastId).filterNot { it in listed }
            .chunked(SQLITE_VARIABLE_CHUNK)
        val withdrawnDownloads = withdrawn.flatMap { getIdsWithDownloadStateIn(it) }
        withdrawn.forEach { deleteByIds(it) }

        val insertedRowIds = insertIgnoringExisting(episodes.map { it.copy(isNew = false) })
        episodes.forEachIndexed { index, episode ->
            if (insertedRowIds[index] == IGNORED_ROW_ID) refreshFeedFields(episode)
        }

        // Feed order becomes the stored order, numbered from 0, which also clears out the negative
        // positions a run of refreshes leaves behind.
        if (handOrdered) reorder(episodes.map { it.id })
        return withdrawnDownloads
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
     * Caches a chapter list fetched from the publisher's own `podcast:chapters` document.
     *
     * The column is otherwise written only by a feed refresh, which is why this exists separately:
     * a fetched list is the app filling in something the feed pointed at rather than published,
     * and the next refresh is free to overwrite it from the feed if the feed grows an inline list.
     *
     * Storing it at all is what stops the same file being fetched every time the episode sheet is
     * opened, on a document that changes about as often as the episode does.
     */
    @Query("UPDATE episodes SET chapters_json = :chaptersJson WHERE id = :id")
    suspend fun setChaptersJson(id: String, chaptersJson: String)

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

/**
 * How many ids one `IN (:ids)` statement binds at most.
 *
 * SQLite builds before 3.32 — every Android release before 11 — refuse more than 999 variables in
 * a statement; staying under that keeps the limit out of every caller's mind.
 */
private const val SQLITE_VARIABLE_CHUNK = 900

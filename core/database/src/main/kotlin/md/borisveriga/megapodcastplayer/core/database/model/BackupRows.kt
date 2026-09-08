package md.borisveriga.megapodcastplayer.core.database.model

import androidx.room.ColumnInfo

/**
 * Row shapes read only when writing a backup.
 *
 * Each one carries the owning show's `feed_url` beside the episode's `guid` rather than the episode
 * id, because those two strings are what a backup stores: ids are derived from them, so a restore
 * regenerates the same ids without needing a mapping table. Reading them as narrow projections
 * rather than whole entities keeps a library-sized export off the heap.
 */

/**
 * Listening state for one episode the user has actually touched.
 *
 * @property feedUrl the owning show's feed URL.
 * @property guid the episode's feed `guid`.
 * @property positionMs stored playback position.
 * @property isPlayed whether the episode is marked played.
 */
data class EpisodeBackupRow(
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    val guid: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "is_played") val isPlayed: Boolean,
)

/**
 * One entry of the durable queue, resolved to the strings a backup stores.
 *
 * @property feedUrl the owning show's feed URL.
 * @property guid the episode's feed `guid`.
 * @property position the entry's place in the queue, smallest first.
 */
data class QueueBackupRow(
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    val guid: String,
    val position: Int,
)

/**
 * An episode that is downloaded, resolved to the strings a backup stores.
 *
 * @property feedUrl the owning show's feed URL.
 * @property guid the episode's feed `guid`.
 */
data class DownloadBackupRow(
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    val guid: String,
)

/**
 * A saved moment, resolved to the strings a backup stores.
 *
 * @property feedUrl the owning show's feed URL.
 * @property guid the episode's feed `guid`.
 * @property positionMs where in the episode the moment was marked.
 * @property note the user's note, if they added one.
 * @property createdAt when it was saved, epoch milliseconds.
 */
data class MomentBackupRow(
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    val guid: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

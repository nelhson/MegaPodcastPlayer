package md.borisveriga.bpodcat.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode

/**
 * Room representation of an episode.
 *
 * The user-owned columns — `position_ms`, `is_played`, `download_state` — are never written by a
 * feed refresh; see `EpisodeDao.upsertFromFeed`. That separation is what makes it safe to re-parse a
 * feed hundreds of times without losing playback progress.
 */
@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcast_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["podcast_id", "published_at"]),
        Index(value = ["download_state"]),
        // The Latest feed sorts by date across every show. The composite index above cannot serve
        // that: its leading column is `podcast_id`, so a global ORDER BY published_at would scan.
        Index(value = ["published_at"]),
    ],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "podcast_id") val podcastId: String,
    val guid: String,
    val title: String,
    val description: String,
    @ColumnInfo(name = "audio_url") val audioUrl: String,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "published_at") val publishedAt: Long?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "position_ms", defaultValue = "0") val positionMs: Long = 0L,
    @ColumnInfo(name = "is_played", defaultValue = "0") val isPlayed: Boolean = false,
    @ColumnInfo(name = "is_new", defaultValue = "0") val isNew: Boolean = false,
    @ColumnInfo(name = "download_state", defaultValue = "NOT_DOWNLOADED")
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    @ColumnInfo(name = "downloaded_bytes", defaultValue = "0") val downloadedBytes: Long = 0L,
    @ColumnInfo(name = "download_percent", defaultValue = "0") val downloadPercent: Float = 0f,
    /**
     * Where this episode sits in a hand-ordered show, smallest first.
     *
     * Only read for shows whose `source` is `YOUTUBE`, which are the ones a user can reorder; an
     * RSS show is ordered by `published_at` and never looks at this. New videos are prepended at
     * `MIN(sort_order) - 1` so a refresh puts them on top without rewriting every existing row,
     * which is why the column is signed and why negative values are ordinary rather than a bug.
     *
     * The `defaultValue` has to match the `DEFAULT 0` in `MIGRATION_4_5` exactly.
     */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,
    /**
     * Whether the user removed this episode from the show's list.
     *
     * The row is kept rather than deleted, and that is the whole point of the column. An episode id
     * is derived from the feed's GUID, so `EpisodeDao.insertIgnoringExisting` recognises a row that
     * is still there and leaves it alone; a deleted row would simply be inserted again by the next
     * refresh, and the episode the user dismissed would reappear. A tombstone is what makes the
     * dismissal stick.
     *
     * Every query that backs a list filters on it. `replaceForPodcast` is the one thing that clears
     * it, because a rebuild throws the stored episodes away and starts from the feed.
     *
     * The `defaultValue` has to match the `DEFAULT 0` in `MIGRATION_5_6` exactly.
     */
    @ColumnInfo(name = "is_hidden", defaultValue = "0")
    val isHidden: Boolean = false,
)

/** Maps a Room row to the domain model. */
fun EpisodeEntity.asExternalModel(): Episode = Episode(
    id = id,
    podcastId = podcastId,
    guid = guid,
    title = title,
    description = description,
    audioUrl = audioUrl,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    publishedAt = publishedAt?.let(Instant::ofEpochMilli),
    sizeBytes = sizeBytes,
    positionMs = positionMs,
    isPlayed = isPlayed,
    isNew = isNew,
    downloadState = downloadState,
    downloadedBytes = downloadedBytes,
    downloadPercent = downloadPercent,
)

/** Maps the domain model to a Room row. */
fun Episode.asEntity(): EpisodeEntity = EpisodeEntity(
    id = id,
    podcastId = podcastId,
    guid = guid,
    title = title,
    description = description,
    audioUrl = audioUrl,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    publishedAt = publishedAt?.toEpochMilli(),
    sizeBytes = sizeBytes,
    positionMs = positionMs,
    isPlayed = isPlayed,
    isNew = isNew,
    downloadState = downloadState,
    downloadedBytes = downloadedBytes,
    downloadPercent = downloadPercent,
)

package md.borisveriga.bpodcat.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastSource
import md.borisveriga.bpodcat.core.model.PodcastWithCounts

/**
 * Room representation of a subscribed podcast.
 *
 * Timestamps are stored as epoch milliseconds rather than ISO strings so that ordering is a plain
 * integer comparison and no type converter is needed.
 */
@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feed_url"], unique = true)],
)
data class PodcastEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "itunes_id") val itunesId: Long?,
    val title: String,
    val author: String,
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    val description: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_refresh_at") val lastRefreshAt: Long?,
    val etag: String?,
    @ColumnInfo(name = "last_modified") val lastModified: String?,
    @ColumnInfo(name = "auto_refresh") val autoRefresh: Boolean,
    /**
     * Where this show's episode list comes from.
     *
     * The `defaultValue` is mandatory, not decorative: it has to match the `DEFAULT 'RSS'` in
     * `MIGRATION_1_2` exactly, or Room's schema validation rejects the migrated database at open
     * with "Migration didn't properly handle podcasts".
     */
    @ColumnInfo(name = "source", defaultValue = "RSS")
    val source: PodcastSource = PodcastSource.RSS,
    /**
     * Where this show sits in the library, smallest first.
     *
     * The library is ordered by hand — the user drags shows around — so the order is data rather
     * than a property of the row, and a title sort cannot stand in for it. New subscriptions are
     * appended at `MAX(sort_order) + 1`; see `PodcastDao.nextSortOrder`.
     *
     * As with [source], the `defaultValue` has to match the `DEFAULT 0` in `MIGRATION_4_5` exactly.
     */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,
    /**
     * Whether the user pinned this show to the top of the library.
     *
     * A second, coarser ordering above [sortOrder] rather than a reserved block of positions: a
     * library the user drags around would have to renumber every row to keep such a block intact,
     * and a refresh or a new subscription could walk into the middle of it. A flag cannot drift.
     *
     * As with [source], the `defaultValue` has to match the `DEFAULT 0` in `MIGRATION_5_6` exactly.
     */
    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,
)

/**
 * A podcast row joined with the episode counts the library screen shows.
 *
 * @property podcast the embedded podcast row.
 * @property episodeCount total episodes stored locally.
 * @property newEpisodeCount episodes flagged new by the last refresh.
 * @property downloadedCount episodes fully downloaded.
 */
data class PodcastWithCountsEntity(
    @Embedded val podcast: PodcastEntity,
    @ColumnInfo(name = "episode_count") val episodeCount: Int,
    @ColumnInfo(name = "new_episode_count") val newEpisodeCount: Int,
    @ColumnInfo(name = "downloaded_count") val downloadedCount: Int,
)

/** Maps a Room row to the domain model. */
fun PodcastEntity.asExternalModel(): Podcast = Podcast(
    id = id,
    itunesId = itunesId,
    title = title,
    author = author,
    feedUrl = feedUrl,
    artworkUrl = artworkUrl,
    description = description,
    addedAt = Instant.ofEpochMilli(addedAt),
    lastRefreshAt = lastRefreshAt?.let(Instant::ofEpochMilli),
    etag = etag,
    lastModified = lastModified,
    autoRefresh = autoRefresh,
    source = source,
    isPinned = isPinned,
)

/** Maps a joined row to the domain model. */
fun PodcastWithCountsEntity.asExternalModel(): PodcastWithCounts = PodcastWithCounts(
    podcast = podcast.asExternalModel(),
    episodeCount = episodeCount,
    newEpisodeCount = newEpisodeCount,
    downloadedCount = downloadedCount,
)

/** Maps the domain model to a Room row. */
fun Podcast.asEntity(): PodcastEntity = PodcastEntity(
    id = id,
    itunesId = itunesId,
    title = title,
    author = author,
    feedUrl = feedUrl,
    artworkUrl = artworkUrl,
    description = description,
    addedAt = addedAt.toEpochMilli(),
    lastRefreshAt = lastRefreshAt?.toEpochMilli(),
    etag = etag,
    lastModified = lastModified,
    autoRefresh = autoRefresh,
    source = source,
    isPinned = isPinned,
)

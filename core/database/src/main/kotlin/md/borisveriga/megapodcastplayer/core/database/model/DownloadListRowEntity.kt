package md.borisveriga.megapodcastplayer.core.database.model

import androidx.room.ColumnInfo
import md.borisveriga.megapodcastplayer.core.model.DownloadListEntry

/**
 * One finished download joined with its show: only the columns the download list export writes.
 *
 * A projection rather than [EpisodeWithShowEntity], because the export needs the feed URL and has no
 * use for the episode description or the chapters.
 *
 * @property showTitle the show's title.
 * @property feedUrl the show's feed URL.
 * @property episodeTitle the episode's title.
 * @property audioUrl the episode's stored audio URL.
 * @property publishedAt when the episode was published, if known.
 * @property durationMs the episode's length, if known.
 * @property sizeBytes the size on disk, or the feed's size when nothing was measured.
 */
data class DownloadListRowEntity(
    @ColumnInfo(name = "show_title") val showTitle: String,
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    @ColumnInfo(name = "episode_title") val episodeTitle: String,
    @ColumnInfo(name = "audio_url") val audioUrl: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
)

/** Maps the joined row to the domain model. */
fun DownloadListRowEntity.asExternalModel(): DownloadListEntry = DownloadListEntry(
    showTitle = showTitle,
    feedUrl = feedUrl,
    episodeTitle = episodeTitle,
    audioUrl = audioUrl,
    publishedAtMs = publishedAt,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
)

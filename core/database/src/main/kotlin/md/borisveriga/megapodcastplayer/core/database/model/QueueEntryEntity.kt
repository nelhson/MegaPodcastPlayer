package md.borisveriga.megapodcastplayer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * One entry in the "up next" queue.
 *
 * ExoPlayer owns the *live* queue while playing; this table is the durable mirror that survives
 * process death and feeds the watch's library payload.
 *
 * @property episodeId the queued episode.
 * @property position sort order, ascending; gaps are allowed so a reorder is a single update.
 */
@Entity(
    tableName = "queue",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class QueueEntryEntity(
    @PrimaryKey @ColumnInfo(name = "episode_id") val episodeId: String,
    val position: Int,
)

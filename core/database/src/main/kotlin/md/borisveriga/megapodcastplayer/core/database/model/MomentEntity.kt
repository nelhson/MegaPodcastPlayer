package md.borisveriga.megapodcastplayer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import md.borisveriga.megapodcastplayer.core.model.Moment

/**
 * A moment the user marked in an episode.
 *
 * The unique index on `(episode_id, position_ms)` is what makes a restore idempotent: importing the
 * same backup twice replaces the rows it wrote the first time rather than doubling them. It is
 * deliberately *not* what collapses a double tap of the mark button — two presses land milliseconds
 * apart and would each get their own row — which is why `MomentsRepository.mark` looks for a
 * neighbouring moment before writing one. See `MOMENT_MERGE_WINDOW_MS`.
 *
 * The cascade means removing a show removes its episodes' moments two levels down, with no
 * application code involved.
 *
 * @property id row id; moments have no natural key beyond the pair the index already enforces.
 * @property episodeId the episode the moment is in.
 * @property positionMs where in the episode it was marked.
 * @property note the user's own note, or null when they saved without adding one.
 * @property createdAt when it was saved, epoch milliseconds.
 */
@Entity(
    tableName = "moments",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["episode_id", "position_ms"], unique = true),
        Index(value = ["created_at"]),
    ],
)
data class MomentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Maps a Room row to the domain model. */
fun MomentEntity.asExternalModel(): Moment = Moment(
    id = id,
    episodeId = episodeId,
    positionMs = positionMs,
    note = note,
    createdAtMs = createdAt,
)

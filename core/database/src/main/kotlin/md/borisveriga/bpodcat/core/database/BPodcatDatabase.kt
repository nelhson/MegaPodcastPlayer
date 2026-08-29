package md.borisveriga.bpodcat.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import md.borisveriga.bpodcat.core.database.dao.EpisodeDao
import md.borisveriga.bpodcat.core.database.dao.PodcastDao
import md.borisveriga.bpodcat.core.database.dao.QueueDao
import md.borisveriga.bpodcat.core.database.model.EpisodeEntity
import md.borisveriga.bpodcat.core.database.model.PodcastEntity
import md.borisveriga.bpodcat.core.database.model.QueueEntryEntity

/**
 * The app's single Room database.
 *
 * Schemas are exported to `core/database/schemas` (see the Room convention plugin) so that every
 * future migration can be diffed and tested against the committed JSON.
 */
@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        QueueEntryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(BPodcatTypeConverters::class)
abstract class BPodcatDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
}

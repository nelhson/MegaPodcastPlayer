package md.borisveriga.megapodcastplayer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.database.dao.QueueDao
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.database.model.QueueEntryEntity

/**
 * The app's single Room database.
 *
 * The version stays at 1 and no schema is exported: this is a personal build with nothing to
 * migrate from, so a schema change recreates the tables (see the destructive fallback in
 * `DatabaseModule`) rather than being carried forward.
 */
@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        QueueEntryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(MegaPodcastPlayerTypeConverters::class)
abstract class MegaPodcastPlayerDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
}

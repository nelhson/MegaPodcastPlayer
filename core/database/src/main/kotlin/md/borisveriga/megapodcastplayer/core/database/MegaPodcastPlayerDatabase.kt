package md.borisveriga.megapodcastplayer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.MomentDao
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.database.dao.QueueDao
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.MomentEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.database.model.QueueEntryEntity

/**
 * The app's single Room database.
 *
 * No schema is exported and there are no migrations: this is a personal build with nothing to
 * migrate from, so a version bump recreates the tables (see the destructive fallback in
 * `DatabaseModule`) rather than being carried forward.
 *
 * That is why the version is bumped as rarely as possible and never for one feature at a time.
 * Version 2 carries the chapter columns on `episodes` *and* the `moments` table together, even
 * though the Moments feature landed after Chapters: the wipe is priced per version, so paying it
 * twice in quick succession would cost the user their library twice for no reason. Anything that
 * needs the schema again should wait for the next deliberate bump — and the backup in Settings is
 * what makes that bump survivable at all.
 */
@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        QueueEntryEntity::class,
        MomentEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(MegaPodcastPlayerTypeConverters::class)
abstract class MegaPodcastPlayerDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
    abstract fun momentDao(): MomentDao
}

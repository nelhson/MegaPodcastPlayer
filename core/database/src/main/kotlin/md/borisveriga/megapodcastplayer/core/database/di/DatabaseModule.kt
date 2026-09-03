package md.borisveriga.megapodcastplayer.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.database.dao.QueueDao
import md.borisveriga.megapodcastplayer.core.database.migration.MIGRATION_1_2
import md.borisveriga.megapodcastplayer.core.database.migration.MIGRATION_2_3
import md.borisveriga.megapodcastplayer.core.database.migration.MIGRATION_3_4
import md.borisveriga.megapodcastplayer.core.database.migration.MIGRATION_4_5

/** Provides the Room database and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(
        @ApplicationContext context: Context,
    ): MegaPodcastPlayerDatabase = Room.databaseBuilder(
        context = context,
        klass = MegaPodcastPlayerDatabase::class.java,
        name = "megapodcastplayer.db",
    )
        // Deliberately no fallbackToDestructiveMigration(): a forgotten schema change must fail
        // loudly at open rather than silently wipe the user's library and playback positions.
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()

    @Provides
    fun providesPodcastDao(database: MegaPodcastPlayerDatabase): PodcastDao = database.podcastDao()

    @Provides
    fun providesEpisodeDao(database: MegaPodcastPlayerDatabase): EpisodeDao = database.episodeDao()

    @Provides
    fun providesQueueDao(database: MegaPodcastPlayerDatabase): QueueDao = database.queueDao()
}

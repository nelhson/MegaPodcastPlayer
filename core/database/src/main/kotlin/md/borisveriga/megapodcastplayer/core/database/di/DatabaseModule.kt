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
        // Personal build: a schema change wipes and recreates rather than migrating. Shows are
        // re-added and downloads re-fetched, which is cheaper than a migration path nobody needs.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    @Provides
    fun providesPodcastDao(database: MegaPodcastPlayerDatabase): PodcastDao = database.podcastDao()

    @Provides
    fun providesEpisodeDao(database: MegaPodcastPlayerDatabase): EpisodeDao = database.episodeDao()

    @Provides
    fun providesQueueDao(database: MegaPodcastPlayerDatabase): QueueDao = database.queueDao()
}

package md.borisveriga.megapodcastplayer.backup.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.backup.RestoreScheduler
import md.borisveriga.megapodcastplayer.core.data.backup.LibraryRestorer

/**
 * Hands `:core:data` back the restore implementation it declares but cannot build.
 *
 * A restore has to outlive the screen that started it, which means WorkManager, which means `:app`
 * — the module graph runs the other way, so the binding lives here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {

    @Binds
    @Singleton
    abstract fun bindsLibraryRestorer(implementation: RestoreScheduler): LibraryRestorer
}

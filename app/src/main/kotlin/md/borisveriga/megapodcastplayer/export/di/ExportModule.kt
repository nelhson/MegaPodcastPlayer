package md.borisveriga.megapodcastplayer.export.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.data.export.DownloadExporter
import md.borisveriga.megapodcastplayer.export.DownloadExportScheduler

/**
 * Hands `:core:data` back the export implementation it declares but cannot build.
 *
 * An export has to outlive the screen that started it, which means WorkManager, which means `:app`,
 * the same arrangement as `BackupModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {

    /** The WorkManager-backed exporter. */
    @Binds
    @Singleton
    abstract fun bindsDownloadExporter(implementation: DownloadExportScheduler): DownloadExporter
}

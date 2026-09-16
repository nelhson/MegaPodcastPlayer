package md.borisveriga.megapodcastplayer.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.data.chapters.EpisodeChapterSource
import md.borisveriga.megapodcastplayer.core.data.export.DocumentTreeExportDirectory
import md.borisveriga.megapodcastplayer.core.data.export.ExportDirectory
import md.borisveriga.megapodcastplayer.core.data.repository.AutoDownloadScheduler
import md.borisveriga.megapodcastplayer.core.data.repository.BackupRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DefaultBackupRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DefaultMomentsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DefaultPlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DefaultShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DefaultUiPreferencesRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.MediaDownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.MomentsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.OfflineFirstPodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.UiPreferencesRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackChapterSource
import md.borisveriga.megapodcastplayer.core.media.PlaybackProgressRecorder
import md.borisveriga.megapodcastplayer.core.media.PlaybackQueueSource
import md.borisveriga.megapodcastplayer.core.media.download.DownloadStatusRecorder

/** Binds repository implementations to their interfaces. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindsBackupRepository(
        implementation: DefaultBackupRepository,
    ): BackupRepository

    @Binds
    @Singleton
    abstract fun bindsPodcastRepository(
        implementation: OfflineFirstPodcastRepository,
    ): PodcastRepository

    @Binds
    @Singleton
    abstract fun bindsPlaybackRepository(
        implementation: DefaultPlaybackRepository,
    ): PlaybackRepository

    /**
     * Satisfies `:core:media`'s read-side dependency.
     *
     * The service cannot depend on `:core:data` — the module graph runs the other way — so this is
     * where the concrete implementation is handed back to it. [DefaultPlaybackRepository] is a
     * singleton, so all three bindings resolve to the same instance.
     */
    @Binds
    @Singleton
    abstract fun bindsPlaybackQueueSource(
        implementation: DefaultPlaybackRepository,
    ): PlaybackQueueSource

    /** Satisfies `:core:media`'s write-side dependency; see [bindsPlaybackQueueSource]. */
    @Binds
    @Singleton
    abstract fun bindsPlaybackProgressRecorder(
        implementation: DefaultPlaybackRepository,
    ): PlaybackProgressRecorder

    /**
     * Lets the playback service resolve the loaded episode's chapters, so the notification's
     * previous and next mean the same thing the player screen's do; see [bindsPlaybackQueueSource]
     * for why the dependency points this way.
     */
    @Binds
    @Singleton
    abstract fun bindsPlaybackChapterSource(
        implementation: EpisodeChapterSource,
    ): PlaybackChapterSource

    @Binds
    @Singleton
    abstract fun bindsDownloadRepository(
        implementation: MediaDownloadRepository,
    ): DownloadRepository

    /**
     * Lets a feed refresh tell the download stack about new episodes without depending on it.
     *
     * [MediaDownloadRepository] is a singleton, so this and [bindsDownloadRepository] resolve to
     * the same instance.
     */
    @Binds
    @Singleton
    abstract fun bindsAutoDownloadScheduler(
        implementation: MediaDownloadRepository,
    ): AutoDownloadScheduler

    /** Satisfies `:core:media`'s download write-side dependency. */
    @Binds
    @Singleton
    abstract fun bindsDownloadStatusRecorder(
        implementation: MediaDownloadRepository,
    ): DownloadStatusRecorder

    @Binds
    @Singleton
    abstract fun bindsMomentsRepository(
        implementation: DefaultMomentsRepository,
    ): MomentsRepository

    @Binds
    @Singleton
    abstract fun bindsUiPreferencesRepository(
        implementation: DefaultUiPreferencesRepository,
    ): UiPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindsShowSettingsRepository(
        implementation: DefaultShowSettingsRepository,
    ): ShowSettingsRepository

    /** The folder an export writes into: a Storage Access Framework tree the user picked. */
    @Binds
    @Singleton
    abstract fun bindsExportDirectory(
        implementation: DocumentTreeExportDirectory,
    ): ExportDirectory
}

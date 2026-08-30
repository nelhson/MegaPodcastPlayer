package md.borisveriga.bpodcat.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.bpodcat.core.data.repository.AutoDownloadScheduler
import md.borisveriga.bpodcat.core.data.repository.DefaultPlaybackRepository
import md.borisveriga.bpodcat.core.data.repository.DefaultUiPreferencesRepository
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.data.repository.MediaDownloadRepository
import md.borisveriga.bpodcat.core.data.repository.OfflineFirstPodcastRepository
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.data.repository.UiPreferencesRepository
import md.borisveriga.bpodcat.core.media.PlaybackProgressRecorder
import md.borisveriga.bpodcat.core.media.PlaybackQueueSource
import md.borisveriga.bpodcat.core.media.download.DownloadStatusRecorder

/** Binds repository implementations to their interfaces. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

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
    abstract fun bindsUiPreferencesRepository(
        implementation: DefaultUiPreferencesRepository,
    ): UiPreferencesRepository
}

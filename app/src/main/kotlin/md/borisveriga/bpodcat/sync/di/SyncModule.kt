package md.borisveriga.bpodcat.sync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.bpodcat.sync.NewEpisodeNotifier
import md.borisveriga.bpodcat.sync.SystemNewEpisodeNotifier

/**
 * Wiring for the background refresh.
 *
 * Only the notifier needs binding: the worker is built by Hilt's `WorkerFactory` and the scheduler
 * has an `@Inject` constructor.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    /** Binds the platform notifier behind the interface `RefreshWorker` depends on. */
    @Binds
    @Singleton
    abstract fun bindsNewEpisodeNotifier(
        notifier: SystemNewEpisodeNotifier,
    ): NewEpisodeNotifier
}

package md.borisveriga.megapodcastplayer.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Provides the app's coroutine dispatchers and application-wide scope. */
@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    @Provides
    @Dispatcher(MegaPodcastPlayerDispatcher.IO)
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(MegaPodcastPlayerDispatcher.Default)
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * A scope that lives as long as the process.
     *
     * Uses [SupervisorJob] so one failed background write (say, a playback-position save) cannot
     * cancel unrelated work.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun providesApplicationScope(
        @Dispatcher(MegaPodcastPlayerDispatcher.Default) dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

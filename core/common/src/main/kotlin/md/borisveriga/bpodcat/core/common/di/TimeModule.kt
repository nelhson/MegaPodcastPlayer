package md.borisveriga.bpodcat.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Provides the clock used for every timestamp the app writes.
 *
 * Injected rather than read from [java.time.Instant.now] so that refresh and playback tests can pin
 * time instead of sleeping.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun providesClock(): Clock = Clock.systemUTC()
}

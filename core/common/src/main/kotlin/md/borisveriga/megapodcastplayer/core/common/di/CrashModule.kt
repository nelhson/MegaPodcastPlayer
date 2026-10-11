package md.borisveriga.megapodcastplayer.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.crash.LogcatCrashReporter

/**
 * Supplies the one [CrashReporter] every module injects.
 *
 * This lives in `:core:common` rather than in `:app` and `:wear` so that the phone and the watch
 * record failures the same way without the wiring existing twice.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object CrashModule {

    /** @return the device-log reporter; nothing is reported off the device. */
    @Provides
    @Singleton
    fun provideCrashReporter(): CrashReporter = LogcatCrashReporter()
}

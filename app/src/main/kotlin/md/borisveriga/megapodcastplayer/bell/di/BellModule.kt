package md.borisveriga.megapodcastplayer.bell.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.bell.SystemBellRinger
import md.borisveriga.megapodcastplayer.core.media.BellRinger

/**
 * Binds the end-of-episode bell's one implementation.
 *
 * The same inversion `:core:data` uses for the playback service's persistence ports: `:core:media`
 * declares [BellRinger] and injects it, and the module that owns a notification channel and the
 * Data Layer — this one — supplies it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BellModule {

    /**
     * @param ringer the phone-and-watch implementation.
     * @return it, as the port the playback service asks for.
     */
    @Binds
    @Singleton
    abstract fun bindsBellRinger(ringer: SystemBellRinger): BellRinger
}

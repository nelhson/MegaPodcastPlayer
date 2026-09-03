package md.borisveriga.megapodcastplayer.wearsync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.wearsync.EpisodeAudioTransfers
import md.borisveriga.megapodcastplayer.wearsync.ForegroundEpisodeAudioTransfers

/**
 * Binds how an episode's audio gets to the watch.
 *
 * Kept out of [WearableModule], which provides Play Services' own clients and nothing else: this one
 * is a decision about *this* app — that a transfer is worth a foreground service — rather than a
 * handle on a platform API.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WearTransferModule {

    @Binds
    @Singleton
    abstract fun bindsEpisodeAudioTransfers(
        transfers: ForegroundEpisodeAudioTransfers,
    ): EpisodeAudioTransfers
}

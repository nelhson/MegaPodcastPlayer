package md.borisveriga.megapodcastplayer.core.media.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import md.borisveriga.megapodcastplayer.core.media.download.CacheDownloadedAudioReader
import md.borisveriga.megapodcastplayer.core.media.download.DownloadedAudioReader

/**
 * Binds the reader that gets downloaded audio back out of the cache.
 *
 * Separate from [DownloadModule] only because that one is an `object` of `@Provides`, and a binding
 * needs an abstract class.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadedAudioModule {

    /** The reader over the one download cache. */
    @Binds
    abstract fun bindsDownloadedAudioReader(
        implementation: CacheDownloadedAudioReader,
    ): DownloadedAudioReader
}

package md.borisveriga.megapodcastplayer.core.media.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import md.borisveriga.megapodcastplayer.core.media.DeviceVideoDecoders
import md.borisveriga.megapodcastplayer.core.youtube.VideoDecoderCheck

/**
 * Tells the YouTube resolver what this device can decode.
 *
 * The interface is `:core:youtube`'s and the answer is this module's, so the binding is here: the
 * resolver stays free of the platform's codec list, and the player side owns what it knows.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class VideoDecodersModule {

    /** The device's own codec list, as the check the resolver asks. */
    @Binds
    abstract fun bindsVideoDecoderCheck(implementation: DeviceVideoDecoders): VideoDecoderCheck
}

package md.borisveriga.bpodcat.core.youtube.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.bpodcat.core.youtube.NewPipeAudioResolver
import md.borisveriga.bpodcat.core.youtube.YouTubeAudioResolver

/**
 * Binds the YouTube audio resolver.
 *
 * The binding is a singleton because the implementation caches resolved URLs and serialises
 * extraction across every caller; a second instance would defeat both.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class YouTubeModule {

    @Binds
    @Singleton
    abstract fun bindsYouTubeAudioResolver(impl: NewPipeAudioResolver): YouTubeAudioResolver
}

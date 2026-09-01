package md.borisveriga.bpodcat.core.youtube.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.bpodcat.core.youtube.NewPipeAudioResolver
import md.borisveriga.bpodcat.core.youtube.NewPipePlaylistFetcher
import md.borisveriga.bpodcat.core.youtube.YouTubeAudioResolver
import md.borisveriga.bpodcat.core.youtube.YouTubePlaylistFetcher

/**
 * Binds the two halves of YouTube support: reading a playlist, and playing a video out of it.
 *
 * Both bindings are singletons, and for the same underlying reason — each holds state that a second
 * instance would defeat. The resolver caches resolved URLs and serialises extraction across every
 * caller; the fetcher shares the one-shot NewPipe bootstrap with it.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class YouTubeModule {

    @Binds
    @Singleton
    abstract fun bindsYouTubeAudioResolver(impl: NewPipeAudioResolver): YouTubeAudioResolver

    @Binds
    @Singleton
    abstract fun bindsYouTubePlaylistFetcher(impl: NewPipePlaylistFetcher): YouTubePlaylistFetcher
}

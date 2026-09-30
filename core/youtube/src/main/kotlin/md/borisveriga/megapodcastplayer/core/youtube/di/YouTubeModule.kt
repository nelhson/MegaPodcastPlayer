package md.borisveriga.megapodcastplayer.core.youtube.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.youtube.NewPipeAudioResolver
import md.borisveriga.megapodcastplayer.core.youtube.NewPipePlaylistFetcher
import md.borisveriga.megapodcastplayer.core.youtube.YouTubeAudioResolver
import md.borisveriga.megapodcastplayer.core.youtube.YouTubePlaylistFetcher
import md.borisveriga.megapodcastplayer.core.youtube.YouTubeVideoResolver

/**
 * Binds the halves of YouTube support: reading a playlist, and playing a video out of it as sound
 * or as sound and picture.
 *
 * Every binding is a singleton, and for the same underlying reason — each holds state that a second
 * instance would defeat. The resolver caches extractions and serialises them across every caller,
 * and the audio and video interfaces are bound to the *same* instance so that one extraction serves
 * both halves of a video; the fetcher shares the one-shot NewPipe bootstrap with it.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class YouTubeModule {

    @Binds
    @Singleton
    abstract fun bindsYouTubeAudioResolver(impl: NewPipeAudioResolver): YouTubeAudioResolver

    @Binds
    @Singleton
    abstract fun bindsYouTubeVideoResolver(impl: NewPipeAudioResolver): YouTubeVideoResolver

    @Binds
    @Singleton
    abstract fun bindsYouTubePlaylistFetcher(impl: NewPipePlaylistFetcher): YouTubePlaylistFetcher
}

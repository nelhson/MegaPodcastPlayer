package md.borisveriga.megapodcastplayer.core.youtube.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.youtube.NewPipeAudioResolver
import md.borisveriga.megapodcastplayer.core.youtube.NewPipePlaylistFetcher
import md.borisveriga.megapodcastplayer.core.youtube.OfficialFeedPlaylistFetcher
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
 *
 * The playlist fetcher is bound twice, once per [md.borisveriga.megapodcastplayer.core.model.YouTubeSource],
 * and only under a qualifier; see [ExtractorPlaylists]. The resolvers are bound once, because the
 * official source has no resolver at all: it plays nothing through Media3, and the resolver is told
 * so by `:core:media`, which is where the preference can be read.
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
    @ExtractorPlaylists
    abstract fun bindsExtractorPlaylistFetcher(impl: NewPipePlaylistFetcher): YouTubePlaylistFetcher

    @Binds
    @Singleton
    @OfficialPlaylists
    abstract fun bindsOfficialPlaylistFetcher(impl: OfficialFeedPlaylistFetcher): YouTubePlaylistFetcher
}

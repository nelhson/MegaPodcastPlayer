package md.borisveriga.megapodcastplayer.core.youtube.di

import javax.inject.Qualifier

/**
 * The [md.borisveriga.megapodcastplayer.core.youtube.YouTubePlaylistFetcher] that reads a whole
 * playlist with the extractor: the [md.borisveriga.megapodcastplayer.core.model.YouTubeSource.EXTRACTOR]
 * source.
 *
 * Both fetchers are bound under a qualifier and neither without one, so that no injection site can
 * take "a playlist fetcher" without saying which. The choice between them is the user's, and is made
 * by the repository on every fetch; a site that injected one unqualified would have made it at build
 * time instead.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ExtractorPlaylists

/**
 * The [md.borisveriga.megapodcastplayer.core.youtube.YouTubePlaylistFetcher] that reads what YouTube
 * publishes and no more: the [md.borisveriga.megapodcastplayer.core.model.YouTubeSource.OFFICIAL]
 * source. See [ExtractorPlaylists] for why both are qualified.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class OfficialPlaylists

package md.borisveriga.megapodcastplayer.core.youtube

import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.network.rss.FeedChannel
import md.borisveriga.megapodcastplayer.core.network.rss.RssParseException
import md.borisveriga.megapodcastplayer.core.network.rss.YouTubeAtomFeedDataSource

/**
 * Reads a playlist from the feed YouTube publishes for it, and no further.
 *
 * The [YouTubeSource.OFFICIAL] half of [YouTubePlaylistFetcher]. Where [NewPipePlaylistFetcher]
 * walks YouTube's own responses to the end of a playlist, this asks for the one document YouTube
 * means to be read by third parties and takes what it gives: the newest entries of an uploads
 * playlist, fifteen at most, with no way to ask for more. Nothing here touches the extractor, so
 * nothing here is against YouTube's terms.
 *
 * The result is cut to [YouTubeSource.OFFICIAL_EPISODE_LIMIT] here rather than left at the feed's
 * fifteen, so that the number a show has under this source is the app's decision in one place: the
 * same constant trims the stored list when it is read, and a show switched to this source shows the
 * same count as one added under it.
 *
 * @property atomFeed downloads and parses the feed.
 */
@Singleton
internal class OfficialFeedPlaylistFetcher @Inject constructor(
    private val atomFeed: YouTubeAtomFeedDataSource,
) : YouTubePlaylistFetcher {

    override suspend fun fetch(playlistId: String): FeedChannel {
        val channel = try {
            atomFeed.fetch(playlistId)
        } catch (e: RssParseException) {
            // A private or deleted playlist answers with an HTML page and a 200, which the parser
            // rejects. Translated so the repository sees the same failure the extractor reports for
            // the same playlist, and the UI words it the same way. A plain IOException — the
            // network — passes through untouched, as it does from the extractor.
            throw YouTubePlaylistUnavailableException(
                playlistId = playlistId,
                reason = "the playlist is private or no longer exists",
                cause = e,
            )
        }
        return channel.copy(items = channel.items.take(YouTubeSource.OFFICIAL_EPISODE_LIMIT))
    }
}

package md.borisveriga.megapodcastplayer.core.model.backup

import md.borisveriga.megapodcastplayer.core.model.PodcastLink
import md.borisveriga.megapodcastplayer.core.model.PodcastLinkParser
import md.borisveriga.megapodcastplayer.core.model.episodeIdOf
import md.borisveriga.megapodcastplayer.core.model.podcastIdOf
import md.borisveriga.megapodcastplayer.core.model.youTubePlaylistFeedUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the property the whole backup feature rests on.
 *
 * A backup stores a `(feedUrl, guid)` pair, never a row id, and restore re-adds the feed URL through
 * the ordinary add path before re-applying state by primary key. That only works if a stored feed
 * URL survives the round trip through [PodcastLinkParser] unchanged — if any branch normalised,
 * re-cased or re-spelled it, [podcastIdOf] would hash to a different show and every restored
 * position would land nowhere.
 *
 * These tests fail the moment that stops being true, which is precisely when a restore would start
 * silently losing a library.
 */
class BackupIdDerivationTest {

    @Test
    fun `an rss feed url round-trips to the same podcast id`() {
        val feedUrl = "https://feeds.simplecast.com/podlodka"

        val reAdded = PodcastLinkParser.parse(feedUrl) as PodcastLink.Rss

        assertEquals(podcastIdOf(feedUrl), podcastIdOf(reAdded.feedUrl))
    }

    @Test
    fun `an rss feed url carrying a query round-trips to the same podcast id`() {
        val feedUrl = "https://example.com/feed.xml?format=rss&token=abc"

        val reAdded = PodcastLinkParser.parse(feedUrl) as PodcastLink.Rss

        assertEquals(podcastIdOf(feedUrl), podcastIdOf(reAdded.feedUrl))
    }

    @Test
    fun `a youtube atom feed url round-trips to the same podcast id`() {
        // What a YouTube-sourced show actually stores in `feed_url`.
        val feedUrl = youTubePlaylistFeedUrl("PLrAXtmRdnEQy6nuLMfO6uJbgQ2gGfBOhE")

        val reAdded = PodcastLinkParser.parse(feedUrl) as PodcastLink.YouTubePlaylist
        val reMinted = youTubePlaylistFeedUrl(reAdded.playlistId)

        assertEquals(feedUrl, reMinted)
        assertEquals(podcastIdOf(feedUrl), podcastIdOf(reMinted))
    }

    @Test
    fun `episode ids follow from the restored podcast id`() {
        val feedUrl = youTubePlaylistFeedUrl("PLrAXtmRdnEQy6nuLMfO6uJbgQ2gGfBOhE")
        val guid = "yt:video:dQw4w9WgXcQ"

        val reAdded = PodcastLinkParser.parse(feedUrl) as PodcastLink.YouTubePlaylist
        val reMinted = youTubePlaylistFeedUrl(reAdded.playlistId)

        assertEquals(
            episodeIdOf(podcastIdOf(feedUrl), guid),
            episodeIdOf(podcastIdOf(reMinted), guid),
        )
    }

    @Test
    fun `surrounding whitespace cannot change a podcast id`() {
        // A backup edited by hand, or a document provider that appended a newline.
        assertEquals(
            podcastIdOf("https://feeds.simplecast.com/podlodka"),
            podcastIdOf("  https://feeds.simplecast.com/podlodka\n"),
        )
    }

    @Test
    fun `surrounding whitespace cannot change an episode id`() {
        val podcastId = podcastIdOf("https://feeds.simplecast.com/podlodka")

        assertEquals(episodeIdOf(podcastId, "guid-1"), episodeIdOf(podcastId, " guid-1 "))
    }
}

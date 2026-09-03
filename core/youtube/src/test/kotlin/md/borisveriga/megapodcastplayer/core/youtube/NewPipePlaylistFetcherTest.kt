package md.borisveriga.megapodcastplayer.core.youtube

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Tests the decisions inside the playlist fetcher that can be made without a network.
 *
 * Extraction itself is untestable offline and deliberately untested, for the same reason
 * [NewPipeAudioResolverTest] gives. What matters here is the part that was actually broken: whether
 * the walk reaches the end of a playlist. The old implementation read a fifteen-entry Atom feed with
 * no continuation at all, so a 34-video playlist imported as 15 — and since those fifteen are the
 * *first* fifteen in playlist order rather than the newest, a video added at the end was invisible
 * forever, refresh included.
 */
class NewPipePlaylistFetcherTest {

    private fun video(
        videoId: String,
        title: String = "Video $videoId",
        durationSeconds: Long = 0,
        uploadedAt: Instant? = null,
        shortDescription: String? = null,
    ): StreamInfoItem = StreamInfoItem(
        ServiceList.YouTube.serviceId,
        "https://www.youtube.com/watch?v=$videoId",
        title,
        StreamType.VIDEO_STREAM,
    ).apply {
        duration = durationSeconds
        uploadedAt?.let {
            uploadDate = DateWrapper(OffsetDateTime.ofInstant(it, ZoneOffset.UTC))
        }
        shortDescription?.let { setShortDescription(it) }
    }

    private fun page(items: List<StreamInfoItem>, next: String? = null) =
        PlaylistPage(items, next?.let(::Page))

    // --- collectPlaylistItems ---------------------------------------------

    @Test
    fun `returns the first page when there is no continuation`() = runTest {
        val only = page(listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb")))

        val items = collectPlaylistItems(only) { error("must not ask for another page") }

        assertEquals(2, items.size)
    }

    @Test
    fun `follows continuations to the end of the playlist`() = runTest {
        // The regression test for the reported bug, at the scale it was reported: a playlist of 34
        // arriving as 15 because nothing followed the continuation.
        val first = page((1..15).map { video("video%06d".format(it)) }, next = "page2")
        val rest = page((16..34).map { video("video%06d".format(it)) })

        val items = collectPlaylistItems(first) { rest }

        assertEquals(34, items.size)
        assertEquals("https://www.youtube.com/watch?v=video000001", items.first().url)
        assertEquals("https://www.youtube.com/watch?v=video000034", items.last().url)
    }

    @Test
    fun `walks many pages, keeping playlist order across them`() = runTest {
        val pages = (0 until 5).map { index ->
            page(
                items = (0 until 3).map { video("video%06d".format(index * 3 + it)) },
                next = "page${index + 1}".takeIf { index < 4 },
            )
        }

        val items = collectPlaylistItems(pages.first()) { token ->
            pages[token.url.removePrefix("page").toInt()]
        }

        assertEquals(15, items.size)
        assertEquals(
            (0 until 15).map { "https://www.youtube.com/watch?v=video%06d".format(it) },
            items.map { it.url },
        )
    }

    @Test
    fun `stops at the page cap rather than following a server forever`() = runTest {
        // A continuation token that never becomes null must not spin on a phone. The bound exists to
        // terminate the loop, not to limit playlists: it sits an order of magnitude above YouTube's
        // own five-thousand-video ceiling.
        var requested = 0
        val endless = page(listOf(video("video000000")), next = "more")

        val items = collectPlaylistItems(endless) {
            requested++
            page(listOf(video("video%06d".format(requested))), next = "more")
        }

        assertEquals(MAX_PLAYLIST_PAGES - 1, requested)
        assertEquals(MAX_PLAYLIST_PAGES, items.size)
    }

    @Test
    fun `stops when a page comes back empty despite promising another`() = runTest {
        // The other way a walk could fail to terminate: pages that keep claiming a successor while
        // yielding nothing. Whatever the token says, a page with no videos ends the playlist.
        var requested = 0

        val items = collectPlaylistItems(page(listOf(video("video000000")), next = "more")) {
            requested++
            page(emptyList(), next = "more")
        }

        assertEquals(1, requested)
        assertEquals(1, items.size)
    }

    @Test
    fun `counts a video listed twice once, in the position it first appeared`() = runTest {
        // A playlist may legitimately list the same video twice. Both copies share a guid, so the
        // database would collapse them anyway; collapsing here keeps the imported count honest.
        val first = page(listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb")), next = "page2")
        val second = page(listOf(video("aaaaaaaaaaa"), video("ccccccccccc")))

        val items = collectPlaylistItems(first) { second }

        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc")
                .map { "https://www.youtube.com/watch?v=$it" },
            items.map { it.url },
        )
    }

    // --- asFeedItemOrNull --------------------------------------------------

    @Test
    fun `maps a video onto the same feed item shape the atom parser produced`() {
        val item = video(
            "niTJ2221aS8",
            title = "Episode one",
            durationSeconds = 3600,
            uploadedAt = Instant.parse("2026-01-10T16:01:05Z"),
            shortDescription = "notes",
        ).asFeedItemOrNull()!!

        // Byte-identical to the Atom <id>, which is what stops a show imported before this change
        // from acquiring a second copy of every episode it already has.
        assertEquals("yt:video:niTJ2221aS8", item.guid)
        assertEquals("Episode one", item.title)
        assertEquals("notes", item.description)
        assertEquals("youtube://video/niTJ2221aS8", item.audioUrl)
        assertEquals("https://i.ytimg.com/vi/niTJ2221aS8/mqdefault.jpg", item.artworkUrl)
        assertEquals(Instant.parse("2026-01-10T16:01:05Z"), item.publishedAt)
        // The Atom feed published no duration, so this used to stay null until first play.
        assertEquals(3_600_000L, item.durationMs)
        assertNull(item.audioLengthBytes)
    }

    @Test
    fun `leaves the duration unset when the extractor does not know it`() {
        assertNull(video("niTJ2221aS8", durationSeconds = 0).asFeedItemOrNull()?.durationMs)
    }

    @Test
    fun `drops an entry whose url carries no usable video id`() {
        // What a video deleted out from under a playlist looks like. Dropping it matches the RSS
        // parser dropping an item with no enclosure: without an id there is no audio to resolve.
        val noId = StreamInfoItem(
            ServiceList.YouTube.serviceId,
            "https://www.youtube.com/playlist?list=PLAA9qRhhXQ2c",
            "Not a video",
            StreamType.VIDEO_STREAM,
        )

        assertNull(noId.asFeedItemOrNull())
    }

    // --- playlistAsFeedChannel ---------------------------------------------

    @Test
    fun `takes the show artwork from the first video`() {
        // A playlist publishes no artwork of its own, so the first video's thumbnail stands in —
        // the same choice the Atom parser made, for the reasons in youTubeThumbnailUrl.
        val channel = playlistAsFeedChannel(
            title = "Nokta History",
            author = "Nokta",
            description = "A series",
            items = listOf(video("niTJ2221aS8"), video("aHsi-OHI_i8")),
        )

        assertEquals("Nokta History", channel.title)
        assertEquals("Nokta", channel.author)
        assertEquals("A series", channel.description)
        assertEquals("https://i.ytimg.com/vi/niTJ2221aS8/mqdefault.jpg", channel.artworkUrl)
        assertEquals(2, channel.items.size)
    }

    @Test
    fun `a playlist whose entries are all unusable has no artwork rather than a broken url`() {
        val channel = playlistAsFeedChannel(
            title = "Empty",
            author = "Nobody",
            description = "",
            items = emptyList(),
        )

        assertNull(channel.artworkUrl)
        assertTrue(channel.items.isEmpty())
    }
}

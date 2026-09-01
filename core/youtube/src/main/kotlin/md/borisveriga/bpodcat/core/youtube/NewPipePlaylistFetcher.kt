package md.borisveriga.bpodcat.core.youtube

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import md.borisveriga.bpodcat.core.common.di.BPodcatDispatcher
import md.borisveriga.bpodcat.core.common.di.Dispatcher
import md.borisveriga.bpodcat.core.model.youTubeAudioSentinel
import md.borisveriga.bpodcat.core.model.youTubePlaylistUrl
import md.borisveriga.bpodcat.core.model.youTubeThumbnailUrl
import md.borisveriga.bpodcat.core.model.youTubeVideoIdFromWatchUrlOrNull
import md.borisveriga.bpodcat.core.network.rss.FeedChannel
import md.borisveriga.bpodcat.core.network.rss.FeedItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Reads playlists with NewPipeExtractor, one page at a time until the playlist runs out.
 *
 * @property bootstrap one-time NewPipe initialisation, shared with the audio resolver.
 * @property ioDispatcher extraction blocks and does real network work, so it never runs on the
 *   caller's thread. Unlike [NewPipeAudioResolver] this has no reason to be blocking: its only
 *   caller is a coroutine in the repository.
 */
@Singleton
internal class NewPipePlaylistFetcher @Inject constructor(
    private val bootstrap: NewPipeBootstrap,
    @Dispatcher(BPodcatDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : YouTubePlaylistFetcher {

    override suspend fun fetch(playlistId: String): FeedChannel = withContext(ioDispatcher) {
        bootstrap.ensureInitialised()

        // The extractor is given the playlist *page* URL. The Atom feed URL the show is stored
        // under is an identity, not an address, and means nothing to the extractor.
        val url = youTubePlaylistUrl(playlistId)
        val info = translateFailures(playlistId) { PlaylistInfo.getInfo(ServiceList.YouTube, url) }

        val items = collectPlaylistItems(
            first = PlaylistPage(info.relatedItems.orEmpty(), info.nextPage),
        ) { page ->
            translateFailures(playlistId) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
                PlaylistPage(more.items.orEmpty(), more.nextPage)
            }
        }

        playlistAsFeedChannel(
            title = info.name.orEmpty(),
            author = info.uploaderName.orEmpty(),
            // The Atom feed published no playlist description, so the parser this replaces had to
            // hard-code an empty one. The extractor does report it.
            description = info.description?.content.orEmpty(),
            items = items,
        )
    }
}

/**
 * One page of a playlist walk.
 *
 * A tiny type rather than NewPipe's own `InfoItemsPage`, so that [collectPlaylistItems] — where the
 * paging bug would live if it lived anywhere — can be driven from a test without a network.
 *
 * @property items the videos on this page, in playlist order.
 * @property nextPage the continuation token, or `null` on the last page.
 */
internal data class PlaylistPage(
    val items: List<StreamInfoItem>,
    val nextPage: Page?,
)

/**
 * Walks a playlist from [first] to its end.
 *
 * The whole point of this module: YouTube hands out a playlist about a hundred videos at a time and
 * the caller must keep asking. The Atom feed that used to back this had no continuation at all,
 * which is why long playlists imported short.
 *
 * Three things bound the walk, because "follow the token until it is null" trusts a remote server to
 * terminate a loop on a phone:
 *  - [MAX_PLAYLIST_PAGES], well past YouTube's own five-thousand-video ceiling.
 *  - A page that yields nothing ends the walk, whatever it claims about a next page.
 *  - Videos are keyed by URL, so a playlist that lists one video twice contributes it once. That is
 *    not a judgement call: both copies share a guid, so the database would collapse them anyway, and
 *    counting them separately would only make the "imported N of M" arithmetic lie.
 *
 * Cancellation is checked once per page. A page is one network round trip, which is the only place
 * this can usefully stop.
 *
 * @param first the page already in hand, from the initial extraction.
 * @param more fetches the page a continuation token points at.
 * @return every distinct video in the playlist, in playlist order.
 */
internal suspend fun collectPlaylistItems(
    first: PlaylistPage,
    more: suspend (Page) -> PlaylistPage,
): List<StreamInfoItem> {
    // Insertion-ordered, so "distinct by URL" costs nothing and playlist order survives.
    val collected = LinkedHashMap<String, StreamInfoItem>()

    fun absorb(page: PlaylistPage) {
        page.items.forEach { item ->
            val url = item.url
            if (!url.isNullOrEmpty()) collected.putIfAbsent(url, item)
        }
    }

    absorb(first)
    var next = first.nextPage
    var pages = 1

    while (next != null && pages < MAX_PLAYLIST_PAGES) {
        currentCoroutineContext().ensureActive()
        val page = more(next)
        if (page.items.isEmpty()) break
        absorb(page)
        next = page.nextPage
        pages++
    }

    return collected.values.toList()
}

/**
 * Shapes an extracted playlist as a feed channel.
 *
 * Emitting the same [FeedChannel] the RSS parser emits is what keeps this change confined to this
 * module: the entity mappers, `upsertFromFeed`, the player and the download stack all continue to
 * see a podcast.
 *
 * @param title the playlist's name.
 * @param author the channel that owns the playlist.
 * @param description the playlist's description.
 * @param items the videos, in playlist order.
 */
internal fun playlistAsFeedChannel(
    title: String,
    author: String,
    description: String,
    items: List<StreamInfoItem>,
): FeedChannel {
    val feedItems = items.mapNotNull(StreamInfoItem::asFeedItemOrNull)
    return FeedChannel(
        title = title,
        author = author,
        description = description,
        // The first video's thumbnail, exactly as the Atom parser chose it: a playlist has no
        // artwork of its own, and youTubeThumbnailUrl explains why this particular size.
        artworkUrl = feedItems.firstOrNull()?.artworkUrl,
        items = feedItems,
    )
}

/**
 * Converts one playlist entry into a feed item, or drops it.
 *
 * An entry whose URL carries no well-formed video id is dropped silently, the same way the Atom
 * parser dropped an entry with no `yt:videoId` and the RSS parser drops an item with no enclosure:
 * without an id there is nothing to resolve audio from, so it could never be played. In practice
 * this is what a video deleted out from under a playlist looks like.
 *
 * Two fields differ from what the Atom feed used to supply, both deliberately:
 *  - [FeedItem.durationMs] is now filled in at import. The feed never published a duration, so the
 *    app had to wait for `EpisodeDao.fillMissingDuration` on first play.
 *  - [FeedItem.publishedAt] is the extractor's upload date, which for a playlist listing is often
 *    the *approximate* one behind YouTube's "2 years ago" rather than a timestamp. That is accepted
 *    rather than worked around: the exact date would cost one extraction per video, and a YouTube
 *    show is hand-ordered, so the date is displayed but never sorted on.
 */
internal fun StreamInfoItem.asFeedItemOrNull(): FeedItem? {
    val videoId = url?.let(::youTubeVideoIdFromWatchUrlOrNull) ?: return null
    return FeedItem(
        // Byte-identical to the Atom `<id>` the old parser stored, which is what keeps a show that
        // was imported before this change from acquiring a second copy of every episode it has.
        guid = VIDEO_GUID_PREFIX + videoId,
        title = name.orEmpty(),
        description = shortDescription.orEmpty(),
        audioUrl = youTubeAudioSentinel(videoId),
        // YouTube advertises no byte length, and Media3 learns the real one when a download starts.
        audioLengthBytes = null,
        artworkUrl = youTubeThumbnailUrl(videoId),
        // getDuration() is seconds; zero means the extractor did not know.
        durationMs = duration.takeIf { it > 0L }?.times(MILLIS_PER_SECOND),
        publishedAt = uploadDate?.offsetDateTime()?.toInstant(),
    )
}

/**
 * The namespace YouTube's own Atom feed gave every entry (`yt:video:niTJ2221aS8`).
 *
 * Reproduced rather than invented: it is hashed into the episode id, so changing it would re-import
 * every YouTube episode already in the database as a duplicate.
 */
private const val VIDEO_GUID_PREFIX = "yt:video:"

/**
 * How many pages a single playlist walk will follow.
 *
 * YouTube serves roughly a hundred videos per page and caps a playlist at five thousand, so this is
 * an order of magnitude of headroom. It exists to bound the loop, not to bound the playlist.
 */
internal const val MAX_PLAYLIST_PAGES = 100

private const val MILLIS_PER_SECOND = 1000L

/**
 * Runs an extractor call, translating its failures into something a user can read.
 *
 * A plain `IOException` is deliberately not caught, exactly as in [NewPipeAudioResolver]: it is
 * genuinely transient, and letting the original through keeps the network diagnostics intact.
 *
 * @param playlistId the playlist being read, for the message.
 * @param block the extractor call.
 */
private inline fun <T> translateFailures(playlistId: String, block: () -> T): T =
    try {
        block()
    } catch (e: ReCaptchaException) {
        throw YouTubePlaylistUnavailableException(
            playlistId = playlistId,
            reason = "YouTube is asking for a captcha; try again in a few minutes",
            cause = e,
        )
    } catch (e: ContentNotAvailableException) {
        // Covers a private playlist, a deleted one, and one whose owner's account is gone.
        throw YouTubePlaylistUnavailableException(
            playlistId = playlistId,
            reason = "the playlist is private or no longer exists",
            cause = e,
        )
    } catch (e: ExtractionException) {
        // Almost always means YouTube changed something and the extractor needs updating.
        throw YouTubePlaylistUnavailableException(
            playlistId = playlistId,
            reason = "YouTube changed something this app cannot read yet",
            cause = e,
        )
    }

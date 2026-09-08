package md.borisveriga.megapodcastplayer.core.network.chapters

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import md.borisveriga.megapodcastplayer.core.model.chapters.MAX_CHAPTERS
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Fetches the `podcast:chapters` document a feed points at.
 *
 * The feed carries only the URL — the chapter list itself is a separate JSON file on the
 * publisher's own host, which is why `Episode.chaptersUrl` was stored from the first release and
 * never once read. This is the fetch that was missing.
 *
 * Absolute URLs, like [md.borisveriga.megapodcastplayer.core.network.rss.FeedApi]: chapters live
 * wherever the publisher put them.
 */
interface ChaptersApi {

    /**
     * Downloads a chapters document.
     *
     * @param url the absolute URL from `<podcast:chapters url="…">`.
     * @return the parsed document.
     */
    @GET
    suspend fun getChapters(@Url url: String): ChaptersDocument
}

/**
 * The Podcasting 2.0 chapters format.
 *
 * Deliberately tolerant, unlike everything the app writes to itself: this is a stranger's file, and
 * every field but the list is optional in the wild. `ignoreUnknownKeys` is set on the converter's
 * `Json` instance for the same reason — the spec has grown fields (`toc`, `location`, `endTime`)
 * that this app does not use and must not choke on.
 *
 * @property chapters the chapter list, in publication order.
 */
@Serializable
data class ChaptersDocument(
    val chapters: List<ChapterEntry> = emptyList(),
) {
    /**
     * Converts to the app's own [Chapter], dropping entries that cannot be used.
     *
     * A chapter with no title is dropped rather than shown as a blank row: the whole point of a
     * chapter list is to be read down. A negative start is dropped because it would seek backwards
     * past the beginning of the episode.
     *
     * Truncated at [MAX_CHAPTERS], the same ceiling the feed parser applies. A document with ten
     * thousand entries is either a mistake or an attack, and neither is worth the memory.
     *
     * @return the chapters, in start order.
     */
    fun toChapters(): List<Chapter> = chapters
        .mapNotNull { entry ->
            val title = entry.title?.trim().orEmpty()
            val startMs = entry.startTime?.let { (it * MILLIS_PER_SECOND).toLong() }
            if (title.isEmpty() || startMs == null || startMs < 0L) {
                null
            } else {
                Chapter(
                    startMs = startMs,
                    title = title,
                    imageUrl = entry.image?.takeIf { it.isNotBlank() },
                    url = entry.url?.takeIf { it.isNotBlank() },
                )
            }
        }
        .sortedBy { it.startMs }
        .take(MAX_CHAPTERS)
}

/**
 * One entry of a chapters document.
 *
 * Every field is nullable because every field is missing somewhere in the wild, including the two
 * the format calls required.
 *
 * @property startTime where the chapter begins, in **seconds**; the format's own unit, and the
 *   reason the conversion above exists at all.
 * @property title the chapter's name.
 * @property image artwork for the chapter, spelled `img` in the document.
 * @property url a link the publisher attached.
 */
@Serializable
data class ChapterEntry(
    val startTime: Double? = null,
    val title: String? = null,
    @SerialName("img") val image: String? = null,
    val url: String? = null,
)

/** Chapter start times are seconds in the document and milliseconds everywhere in this app. */
private const val MILLIS_PER_SECOND = 1_000

package md.borisveriga.bpodcat.core.model

/**
 * A show returned by the Apple Podcasts (iTunes) search or lookup endpoint.
 *
 * This is deliberately *not* a [Podcast]: nothing is stored until the user chooses to add it, and a
 * search result may not even be addable (see [feedUrl]).
 *
 * @property itunesId Apple's `collectionId`.
 * @property title Apple's `collectionName`.
 * @property author Apple's `artistName`.
 * @property feedUrl the show's RSS feed. **Nullable**: Apple omits it for shows that are exclusive
 *   to Apple Podcasts, and such a show cannot be added — the UI must say so rather than fail later.
 * @property artworkUrl Apple's `artworkUrl600`.
 * @property episodeCount Apple's `trackCount`, capped by Apple at 300 for large feeds.
 * @property genres Apple's genre labels, used only for display.
 */
data class PodcastSearchResult(
    val itunesId: Long,
    val title: String,
    val author: String,
    val feedUrl: String?,
    val artworkUrl: String?,
    val episodeCount: Int?,
    val genres: List<String>,
)

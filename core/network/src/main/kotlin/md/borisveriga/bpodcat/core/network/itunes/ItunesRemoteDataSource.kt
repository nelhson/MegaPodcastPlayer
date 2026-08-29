package md.borisveriga.bpodcat.core.network.itunes

import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.bpodcat.core.model.PodcastSearchResult

/**
 * Thin domain-facing wrapper over [ItunesApi].
 *
 * Keeps Apple's DTO shape, query defaults and quirks (missing ids, missing feed URLs) out of the
 * repository layer.
 *
 * @property api the Retrofit-generated client.
 */
@Singleton
class ItunesRemoteDataSource @Inject constructor(
    private val api: ItunesApi,
) {

    /**
     * Searches Apple Podcasts.
     *
     * @param term the user's query; blank queries short-circuit to an empty list so a cleared search
     *   field never hits the network.
     * @param limit maximum number of results to return.
     * @param country storefront to search. `US` is used by default because it has the widest
     *   catalogue, including the Russian-language shows in this library.
     * @return matching shows, newest Apple ranking first.
     */
    suspend fun search(
        term: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        country: String = DEFAULT_COUNTRY,
    ): List<PodcastSearchResult> {
        if (term.isBlank()) return emptyList()
        return api.search(term = term.trim(), entity = PODCAST_ENTITY, limit = limit, country = country)
            .results
            .mapNotNull { it.toSearchResultOrNull() }
    }

    /**
     * Resolves a single show by Apple collection id.
     *
     * @param itunesId Apple's `collectionId`.
     * @return the show, or `null` when Apple knows no show with that id.
     */
    suspend fun lookup(itunesId: Long): PodcastSearchResult? =
        api.lookup(id = itunesId, entity = PODCAST_ENTITY)
            .results
            .firstNotNullOfOrNull { it.toSearchResultOrNull() }

    private companion object {
        const val PODCAST_ENTITY = "podcast"
        const val DEFAULT_SEARCH_LIMIT = 25
        const val DEFAULT_COUNTRY = "US"
    }
}

/**
 * Maps an Apple DTO to the domain model, dropping entries that carry no collection id.
 *
 * A result without `collectionId` is not a show (Apple mixes artist rows into some responses), so it
 * can never be added and is filtered out here rather than surfaced as a broken row.
 */
private fun ItunesPodcastDto.toSearchResultOrNull(): PodcastSearchResult? {
    val id = collectionId ?: return null
    return PodcastSearchResult(
        itunesId = id,
        title = collectionName.orEmpty(),
        author = artistName.orEmpty(),
        feedUrl = feedUrl,
        artworkUrl = artworkUrl600 ?: artworkUrl100,
        episodeCount = trackCount,
        genres = genres,
    )
}

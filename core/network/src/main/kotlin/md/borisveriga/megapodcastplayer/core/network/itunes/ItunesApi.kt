package md.borisveriga.megapodcastplayer.core.network.itunes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The public Apple Podcasts (iTunes) endpoints MegaPodcastPlayer uses.
 *
 * Base URL: `https://itunes.apple.com/`.
 *
 * This is an undocumented, unauthenticated API with an informal rate limit of roughly 20 requests
 * per minute per IP, so callers must debounce search input and cache results.
 */
interface ItunesApi {

    /**
     * Full-text search for shows.
     *
     * @param term the user's query.
     * @param entity always `podcast`; other entities return episodes or artists.
     * @param limit maximum number of results (Apple caps this at 200).
     * @param country storefront to search; results and availability differ per storefront.
     */
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("entity") entity: String,
        @Query("limit") limit: Int,
        @Query("country") country: String,
    ): ItunesResponseDto

    /**
     * Resolves a show by its collection id — this is what turns an Apple Podcasts link into an RSS
     * feed URL.
     *
     * @param id Apple's `collectionId`, as extracted from the pasted link.
     * @param entity always `podcast`.
     */
    @GET("lookup")
    suspend fun lookup(
        @Query("id") id: Long,
        @Query("entity") entity: String,
    ): ItunesResponseDto
}

/**
 * Envelope returned by both `search` and `lookup`.
 *
 * @property resultCount number of entries in [results]; `0` for an unknown id or an empty search.
 * @property results the matched shows.
 */
@Serializable
data class ItunesResponseDto(
    val resultCount: Int = 0,
    val results: List<ItunesPodcastDto> = emptyList(),
)

/**
 * A single show as returned by Apple.
 *
 * Every field is optional: Apple omits `feedUrl` for shows exclusive to Apple Podcasts, and the
 * `lookup` endpoint returns a slightly different field set than `search`.
 */
@Serializable
data class ItunesPodcastDto(
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    val feedUrl: String? = null,
    @SerialName("artworkUrl600") val artworkUrl600: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
    val trackCount: Int? = null,
    val primaryGenreName: String? = null,
    val genres: List<String> = emptyList(),
)

package md.borisveriga.megapodcastplayer.core.media.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.media.datasource.ChunkedDataSource
import md.borisveriga.megapodcastplayer.core.media.youtube.YouTubeDataSpecResolver
import md.borisveriga.megapodcastplayer.core.media.youtube.YouTubeInvalidatingDataSource
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoIdOrNull
import md.borisveriga.megapodcastplayer.core.network.di.MegaPodcastPlayerOkHttp
import okhttp3.OkHttpClient

/** The cache that holds downloaded episode audio. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadCache

/**
 * A data source factory that reads [DownloadCache] first and the network second, but never writes
 * to the cache. This is what the player uses.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackDataSource

/**
 * The shared client with its response cache removed, for audio only. See [mediaOkHttpClient].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaOkHttp

/**
 * Provides the Media3 download machinery.
 *
 * Everything here is a singleton for a hard reason rather than a stylistic one: [SimpleCache] takes
 * an exclusive file lock on its directory, so a second instance pointed at the same folder throws.
 * One cache, one [DownloadManager], one process.
 */
@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    /** Directory under the app's private files where episode audio is written. */
    private const val DOWNLOAD_DIRECTORY = "episode_downloads"

    /**
     * How many episodes download at once.
     *
     * Three is enough to keep a connection saturated without turning "download all" into a burst
     * of twenty parallel requests against one publisher's CDN.
     */
    private const val MAX_PARALLEL_DOWNLOADS = 3

    /**
     * How many bytes each YouTube range request asks for. See [ChunkedDataSource].
     *
     * Eight megabytes is a compromise between two costs that pull in opposite directions. Smaller
     * chunks mean more round trips — nine for a typical hour-long talk at this size, against one
     * before chunking existed — while larger ones give `googlevideo.com` more of a single response
     * to apply its rate limit to, which is the entire thing being avoided. Anything in the range of
     * a few megabytes to ten behaves much the same in practice; this is the middle of it.
     */
    private const val YOUTUBE_CHUNK_BYTES = 8L * 1024 * 1024

    /**
     * The network end of both data source chains.
     *
     * Three layers, innermost first, each one doing a single thing:
     *
     *  1. [ResolvingDataSource] wraps OkHttp rather than replacing it, so a `youtube://video/<id>`
     *     URI becomes a real audio URL immediately before the request goes out, while every ordinary
     *     podcast URL passes through untouched.
     *  2. [YouTubeInvalidatingDataSource] watches that request fail and drops the resolution behind
     *     it, so a URL that died before its stated expiry is re-extracted on the retry instead of
     *     being replayed until the download gives up.
     *  3. [ChunkedDataSource] splits a YouTube read into bounded range requests. Media3 otherwise
     *     fetches a whole episode with one un-ranged `GET`, which YouTube serves at roughly playback
     *     speed — the reason a YouTube episode used to take an hour to download and a podcast of the
     *     same length a minute. Podcast URLs are not chunked; they were never throttled, and the
     *     extra round trips would be paid for nothing.
     *
     * Crucially this factory is only ever used as the *upstream* of a cache — never as the whole
     * chain. Both callers below put a [CacheDataSource] above it, which is what keeps cache keys
     * and download ids anchored to the sentinel instead of to a URL that expires within hours, and
     * what keeps chunk boundaries from being visible to anything that matters.
     *
     * The client must be the [MediaOkHttp] one rather than the shared [MegaPodcastPlayerOkHttp] one, or every
     * episode fetched here is also written through OkHttp's response cache. See [mediaOkHttpClient].
     */
    @OptIn(UnstableApi::class)
    private fun networkDataSourceFactory(
        context: Context,
        okHttpClient: OkHttpClient,
        youTubeResolver: YouTubeDataSpecResolver,
    ): DataSource.Factory = ChunkedDataSource.Factory(
        upstreamFactory = YouTubeInvalidatingDataSource.Factory(
            upstreamFactory = ResolvingDataSource.Factory(
                DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient)),
                youTubeResolver,
            ),
            resolver = youTubeResolver,
        ),
        chunkSizeBytes = YOUTUBE_CHUNK_BYTES,
        // Tested against the spec as it was opened, which is still the sentinel at this level: the
        // resolver sits below and has not run yet.
        shouldChunk = { dataSpec -> youTubeVideoIdOrNull(dataSpec.uri.toString()) != null },
    )

    /**
     * The audio client: everything the shared one has, minus the response cache.
     *
     * Built once and shared by both chains so the two do not each hold their own derived client.
     */
    @Provides
    @Singleton
    @MediaOkHttp
    fun providesMediaOkHttpClient(
        @MegaPodcastPlayerOkHttp okHttpClient: OkHttpClient,
    ): OkHttpClient = mediaOkHttpClient(okHttpClient)

    /**
     * The index Media3 keeps of what is downloaded and what is cached.
     *
     * Deliberately a *standalone* database rather than a table in [
     * md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase]: Media3 owns its schema and migrates it
     * on its own release cadence, and mixing it into the app's Room database would make every
     * media3 upgrade an app migration.
     */
    @Provides
    @Singleton
    fun providesDownloadDatabaseProvider(
        @ApplicationContext context: Context,
    ): DatabaseProvider = StandaloneDatabaseProvider(context)

    /**
     * The download cache.
     *
     * [NoOpCacheEvictor] because this cache is not a cache in the usual sense — it is the user's
     * offline library. Nothing may be evicted to make room; episodes leave only when the user or
     * the keep-limit sweep removes them.
     */
    @Provides
    @Singleton
    @DownloadCache
    fun providesDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): Cache = SimpleCache(
        File(context.filesDir, DOWNLOAD_DIRECTORY),
        NoOpCacheEvictor(),
        databaseProvider,
    )

    /**
     * The manager that performs downloads.
     *
     * Its data source factory writes into the cache, which is what distinguishes it from
     * [providesPlaybackDataSourceFactory].
     */
    @Provides
    @Singleton
    fun providesDownloadManager(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
        @DownloadCache cache: Cache,
        @MediaOkHttp okHttpClient: OkHttpClient,
        youTubeResolver: YouTubeDataSpecResolver,
    ): DownloadManager = DownloadManager(
        context,
        databaseProvider,
        cache,
        // Audio shares the app's connection pool with feeds and artwork. Media3 wraps this factory
        // in a cache-writing CacheDataSource itself, so passing the resolver here places it below
        // the cache — the download is indexed by the sentinel, not by the URL it was fetched from.
        networkDataSourceFactory(context, okHttpClient, youTubeResolver),
        Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
    ).apply {
        maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
    }

    /**
     * The player's data source factory: cache-first, network-second, read-only.
     *
     * The write sink is deliberately left unset. If streaming wrote into the download cache, every
     * episode the user merely listened to would start occupying storage that the "Downloads" screen
     * claims is empty, and the keep-limit sweep would have nothing to sweep it with.
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` keeps a corrupt cache entry from being a permanent playback
     * failure: the player falls back to the network and the episode still plays.
     */
    @Provides
    @Singleton
    @PlaybackDataSource
    fun providesPlaybackDataSourceFactory(
        @ApplicationContext context: Context,
        @DownloadCache cache: Cache,
        @MediaOkHttp okHttpClient: OkHttpClient,
        youTubeResolver: YouTubeDataSpecResolver,
    ): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(
            networkDataSourceFactory(context, okHttpClient, youTubeResolver),
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/**
 * Derives the audio client from the shared one by dropping its response cache.
 *
 * ## Why audio must not touch that cache
 *
 * [md.borisveriga.megapodcastplayer.core.network.di.NetworkModule] gives the shared client a 20 MB disk cache,
 * sized for feed XML and iTunes JSON. Media3 opens an episode with no `Range` header, so the CDN
 * answers `200` — which OkHttp considers cacheable — and OkHttp writes the whole body into that
 * cache as the bytes are read. An episode is routinely larger than the cache itself, so
 * `DiskLruCache` evicts *every* existing entry trying to make room and then discards the episode
 * too, having gained nothing.
 *
 * Measured on a 28 MB episode: the cache went from 19.7 MB to 16 KB, taking every cached feed with
 * it. So the cost was paid three times over — each audio byte written to disk twice (here and in
 * the [DownloadCache]), and the next refresh forced to re-fetch feeds that were already held.
 *
 * ## Why `newBuilder` rather than a fresh client
 *
 * `newBuilder()` copies the connection pool, dispatcher, DNS and interceptors by reference, so audio
 * still shares the pool with feeds and artwork — the reason that client is a singleton — and still
 * passes through [md.borisveriga.megapodcastplayer.core.network.HttpsUpgradeInterceptor], which the cleartext
 * enclosure URLs depend on. A separately constructed client would silently lose both.
 *
 * A top-level function rather than a private one so the guarantee can be asserted directly.
 *
 * @param shared the application-wide client.
 * @return the same client, cacheless.
 */
internal fun mediaOkHttpClient(shared: OkHttpClient): OkHttpClient =
    shared.newBuilder().cache(null).build()

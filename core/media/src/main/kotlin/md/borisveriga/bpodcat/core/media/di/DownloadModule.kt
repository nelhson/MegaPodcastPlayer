package md.borisveriga.bpodcat.core.media.di

import android.content.Context
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
import md.borisveriga.bpodcat.core.media.youtube.YouTubeDataSpecResolver
import md.borisveriga.bpodcat.core.network.di.BPodcatOkHttp
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
     * The index Media3 keeps of what is downloaded and what is cached.
     *
     * Deliberately a *standalone* database rather than a table in [
     * md.borisveriga.bpodcat.core.database.BPodcatDatabase]: Media3 owns its schema and migrates it
     * on its own release cadence, and mixing it into the app's Room database would make every
     * media3 upgrade an app migration.
     */
    /**
     * The network end of both data source chains.
     *
     * [ResolvingDataSource] wraps OkHttp rather than replacing it, so a `youtube://video/<id>` URI
     * becomes a real audio URL immediately before the request goes out, while every ordinary
     * podcast URL passes through untouched.
     *
     * Crucially this factory is only ever used as the *upstream* of a cache — never as the whole
     * chain. Both callers below put a [CacheDataSource] above it, which is what keeps cache keys
     * and download ids anchored to the sentinel instead of to a URL that expires within hours.
     */
    private fun networkDataSourceFactory(
        context: Context,
        okHttpClient: OkHttpClient,
        youTubeResolver: YouTubeDataSpecResolver,
    ): DataSource.Factory = ResolvingDataSource.Factory(
        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient)),
        youTubeResolver,
    )

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
        @BPodcatOkHttp okHttpClient: OkHttpClient,
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
        @BPodcatOkHttp okHttpClient: OkHttpClient,
        youTubeResolver: YouTubeDataSpecResolver,
    ): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(
            networkDataSourceFactory(context, okHttpClient, youTubeResolver),
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

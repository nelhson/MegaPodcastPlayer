package md.borisveriga.megapodcastplayer.core.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import md.borisveriga.megapodcastplayer.core.network.BuildConfig
import md.borisveriga.megapodcastplayer.core.network.HttpsUpgradeInterceptor
import md.borisveriga.megapodcastplayer.core.network.itunes.ItunesApi
import md.borisveriga.megapodcastplayer.core.network.rss.FeedApi
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Qualifies the single [OkHttpClient] shared by Retrofit, Coil and (later) Media3, so that
 * connection pool, DNS cache and disk cache are not duplicated three times.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MegaPodcastPlayerOkHttp

/** Provides the HTTP stack and the Retrofit services built on it. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** iTunes Search API base URL. */
    private const val ITUNES_BASE_URL = "https://itunes.apple.com/"

    /** 20 MB is plenty for JSON responses and conditional feed revalidation. */
    private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024

    @Provides
    @Singleton
    fun providesJson(): Json = Json {
        // Apple returns a wide, undocumented and drifting field set; never fail on a new key.
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    @MegaPodcastPlayerOkHttp
    fun providesOkHttpClient(
        @ApplicationContext context: Context,
        httpsUpgrade: HttpsUpgradeInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // First in the chain so everything below it — including the debug logger — sees the URL
        // that is actually requested. Feeds publish cleartext enclosure URLs that Android will not
        // open; see HttpsUpgradeInterceptor.
        .addInterceptor(httpsUpgrade)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                )
            }
        }
        .build()

    @Provides
    @Singleton
    fun providesItunesApi(
        @MegaPodcastPlayerOkHttp client: OkHttpClient,
        json: Json,
    ): ItunesApi = Retrofit.Builder()
        .baseUrl(ITUNES_BASE_URL)
        .callFactory { request -> client.newCall(request) }
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ItunesApi::class.java)

    /**
     * Feed requests always carry an absolute `@Url`, so the base URL here is only a placeholder
     * that Retrofit requires at build time.
     */
    @Provides
    @Singleton
    fun providesFeedApi(
        @MegaPodcastPlayerOkHttp client: OkHttpClient,
    ): FeedApi = Retrofit.Builder()
        .baseUrl(ITUNES_BASE_URL)
        .callFactory { request -> client.newCall(request) }
        .build()
        .create(FeedApi::class.java)
}

package md.borisveriga.bpodcat

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import md.borisveriga.bpodcat.core.data.download.DownloadStateSynchroniser
import md.borisveriga.bpodcat.core.network.di.BPodcatOkHttp
import md.borisveriga.bpodcat.wearsync.NowPlayingPublisher
import okhttp3.OkHttpClient

/**
 * Application entry point.
 *
 * Hosts the Hilt graph and builds Coil's singleton [ImageLoader] on top of the app's single
 * [OkHttpClient], so artwork requests share the same connection pool and DNS cache as the feed and
 * iTunes calls.
 *
 * It is also where download mirroring and the watch bridge start. Both have to happen here rather
 * than in a screen: downloads run while no screen exists, and a download that finished with the app
 * closed is only noticed by the reconciliation pass [DownloadStateSynchroniser] makes on start-up,
 * while the watch has to see playback state whether or not anyone has opened the phone app.
 */
@HiltAndroidApp
class BPodcatApplication : Application(), SingletonImageLoader.Factory {

    /**
     * Injected lazily as a provider: Coil may ask for the loader before the Hilt graph would
     * otherwise be touched, and this keeps that ordering explicit.
     */
    @Inject
    @BPodcatOkHttp
    lateinit var okHttpClient: dagger.Lazy<OkHttpClient>

    /** Mirrors Media3's download index into the episodes table. */
    @Inject
    lateinit var downloadStateSynchroniser: DownloadStateSynchroniser

    /** Mirrors playback state onto the paired watch. */
    @Inject
    internal lateinit var nowPlayingPublisher: NowPlayingPublisher

    override fun onCreate() {
        super.onCreate()
        // Returns immediately; the reconciliation and the event collection run on the application
        // scope. The returned job is only of interest to tests.
        downloadStateSynchroniser.start()
        // Likewise returns immediately. Costs nothing when no watch is paired: the publisher only
        // writes when playback changes, and a write with no peer simply fails and is swallowed.
        nowPlayingPublisher.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get() }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory((context as Context).cacheDir.resolve("image_cache"))
                    .maxSizeBytes(ARTWORK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()

    private companion object {
        /** Artwork is small and highly reused; 64 MB covers a large library comfortably. */
        const val ARTWORK_CACHE_BYTES = 64L * 1024 * 1024
    }
}

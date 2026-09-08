package md.borisveriga.megapodcastplayer

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.download.DownloadStateSynchroniser
import md.borisveriga.megapodcastplayer.core.data.playback.ShowSpeedApplier
import md.borisveriga.megapodcastplayer.core.network.di.MegaPodcastPlayerOkHttp
import md.borisveriga.megapodcastplayer.sync.RefreshScheduler
import md.borisveriga.megapodcastplayer.wearsync.NowPlayingPublisher
import md.borisveriga.megapodcastplayer.wearsync.OfflineLibraryPublisher
import okhttp3.OkHttpClient

/**
 * Application entry point.
 *
 * Hosts the Hilt graph and builds Coil's singleton [ImageLoader] on top of the app's single
 * [OkHttpClient], so artwork requests share the same connection pool and DNS cache as the feed and
 * iTunes calls.
 *
 * It is also where download mirroring, the watch bridge and the periodic feed refresh start. All
 * three have to happen here rather than in a screen: downloads run while no screen exists, and a
 * download that finished with the app closed is only noticed by the reconciliation pass
 * [DownloadStateSynchroniser] makes on start-up; the watch has to see playback state whether or not
 * anyone has opened the phone app; and the refresh exists precisely for the hours nobody does.
 *
 * Injecting [CrashReporter] here is what starts crash reporting for the process. Uncaught
 * exceptions are already covered without any code — Crashlytics installs its handler from a content
 * provider, before this class exists — but the custom keys that make a report legible are set when
 * the reporter is first constructed, and nothing else in `:app` is guaranteed to ask for one.
 *
 * It implements [Configuration.Provider] so WorkManager builds its workers through Hilt. That is
 * also why the manifest removes WorkManager's default `androidx.startup` initializer: with a custom
 * configuration, WorkManager has to be initialised on demand from here instead.
 */
@HiltAndroidApp
class MegaPodcastPlayerApplication :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {

    /**
     * Injected lazily as a provider: Coil may ask for the loader before the Hilt graph would
     * otherwise be touched, and this keeps that ordering explicit.
     */
    @Inject
    @MegaPodcastPlayerOkHttp
    lateinit var okHttpClient: dagger.Lazy<OkHttpClient>

    /** Mirrors Media3's download index into the episodes table. */
    @Inject
    lateinit var downloadStateSynchroniser: DownloadStateSynchroniser

    /** Plays each show at the rate the user set for it, however the episode was started. */
    @Inject
    lateinit var showSpeedApplier: ShowSpeedApplier

    /** Mirrors playback state onto the paired watch. */
    @Inject
    internal lateinit var nowPlayingPublisher: NowPlayingPublisher

    @Inject
    internal lateinit var offlineLibraryPublisher: OfflineLibraryPublisher

    /** Constructed for its side effects; see the class KDoc. */
    @Inject
    lateinit var crashReporter: CrashReporter

    /** Puts the periodic feed refresh on WorkManager's schedule. */
    @Inject
    internal lateinit var refreshScheduler: RefreshScheduler

    /** Builds `@HiltWorker` workers, so [md.borisveriga.megapodcastplayer.sync.RefreshWorker] can inject. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Returns immediately; the reconciliation and the event collection run on the application
        // scope. The returned job is only of interest to tests.
        downloadStateSynchroniser.start()
        // Also returns immediately. It has to live here rather than in the player's view model
        // because the queue advances with no screen open, and that is the transition a per-show
        // speed exists to survive.
        showSpeedApplier.start()
        // Likewise returns immediately. Costs nothing when no watch is paired: the publisher only
        // writes when playback changes, and a write with no peer simply fails and is swallowed.
        nowPlayingPublisher.start()
        // The other half of what the watch reads: which episodes it could take with it. Cheap for
        // the same reason — one Data Layer write when the download list changes, and none at all
        // when it does not.
        offlineLibraryPublisher.start()
        // Cheap and idempotent: WorkManager keeps the run already scheduled, so this is a no-op on
        // every start after the first.
        refreshScheduler.schedule()
        // The last breadcrumb before a start-up crash is otherwise the previous session's, which
        // reads as though the process never restarted.
        crashReporter.log("Phone process started")
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

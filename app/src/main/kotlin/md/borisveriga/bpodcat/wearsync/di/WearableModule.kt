package md.borisveriga.bpodcat.wearsync.di

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the phone's handles on the Wearable Data Layer.
 *
 * The phone publishes state and never initiates a message — the watch does the asking, and its
 * messages arrive through [md.borisveriga.bpodcat.wearsync.WearCommandService] rather than through
 * a client — so there is no `MessageClient` here. The [NodeClient] is not for sending either: it is
 * how [md.borisveriga.bpodcat.wearsync.WearSenderVerifier] answers "is this sender a node we are
 * actually paired with" before a command reaches the player.
 *
 * Injected rather than called statically so that both can be unit-tested against fakes.
 */
@Module
@InstallIn(SingletonComponent::class)
object WearableModule {

    @Provides
    @Singleton
    fun providesDataClient(@ApplicationContext context: Context): DataClient =
        Wearable.getDataClient(context)

    @Provides
    @Singleton
    fun providesNodeClient(@ApplicationContext context: Context): NodeClient =
        Wearable.getNodeClient(context)

    /**
     * The app's one Coil loader, for
     * [md.borisveriga.bpodcat.wearsync.ArtworkAssets].
     *
     * Deliberately the *singleton* loader that
     * [md.borisveriga.bpodcat.BPodcatApplication.newImageLoader] built, not a second one: artwork
     * bound for the watch is nearly always artwork a screen has already shown, so sharing the loader
     * means sharing its disk cache and the fetch usually never leaves the device. Exposed through
     * Hilt rather than looked up statically so a test can substitute a loader that resolves without
     * a network.
     */
    @Provides
    @Singleton
    fun providesImageLoader(@ApplicationContext context: Context): ImageLoader =
        SingletonImageLoader.get(context)
}

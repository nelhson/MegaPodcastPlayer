package md.borisveriga.bpodcat.wear.di

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the watch's handles on the Wearable Data Layer.
 *
 * This is the whole of the watch's "data layer" in both senses of the phrase: there is no database,
 * no HTTP client and no player on this side, only these four clients and the phone at the other end
 * of them.
 */
@Module
@InstallIn(SingletonComponent::class)
object WearableModule {

    /** Receives the playback state the phone publishes. */
    @Provides
    @Singleton
    fun providesDataClient(@ApplicationContext context: Context): DataClient =
        Wearable.getDataClient(context)

    /** Sends commands to the phone. */
    @Provides
    @Singleton
    fun providesMessageClient(@ApplicationContext context: Context): MessageClient =
        Wearable.getMessageClient(context)

    /** Finds the phone that has BPodcat installed, rather than any phone. */
    @Provides
    @Singleton
    fun providesCapabilityClient(@ApplicationContext context: Context): CapabilityClient =
        Wearable.getCapabilityClient(context)

    /** Distinguishes "no phone in range" from "phone in range, app missing". */
    @Provides
    @Singleton
    fun providesNodeClient(@ApplicationContext context: Context): NodeClient =
        Wearable.getNodeClient(context)
}

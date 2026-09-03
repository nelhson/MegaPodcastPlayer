package md.borisveriga.megapodcastplayer.wear.di

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
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
 * These are the watch's whole connection to the phone: state arrives on a data item, commands leave
 * as messages, and one episode's audio at a time crosses on a channel. There is still no database
 * and no HTTP client on this side — the audio the watch plays is audio the phone handed it.
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

    /** Finds the phone that has MegaPodcastPlayer installed, rather than any phone. */
    @Provides
    @Singleton
    fun providesCapabilityClient(@ApplicationContext context: Context): CapabilityClient =
        Wearable.getCapabilityClient(context)

    /** Distinguishes "no phone in range" from "phone in range, app missing". */
    @Provides
    @Singleton
    fun providesNodeClient(@ApplicationContext context: Context): NodeClient =
        Wearable.getNodeClient(context)

    /**
     * Receives episode audio the phone sends.
     *
     * The channel itself is opened by the phone and delivered to
     * [md.borisveriga.megapodcastplayer.wear.data.EpisodeAudioReceiverService]; this client is what turns the
     * handle it is given into a stream.
     */
    @Provides
    @Singleton
    fun providesChannelClient(@ApplicationContext context: Context): ChannelClient =
        Wearable.getChannelClient(context)
}

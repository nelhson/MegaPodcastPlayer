package md.borisveriga.bpodcat.wearsync.di

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
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
 * a client — so there is no `MessageClient` here. It does initiate one thing: a channel, when the
 * watch asks for an episode's audio. The [NodeClient] is not for sending either: it is
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
     * The channel client, which carries episode audio to the watch.
     *
     * A channel rather than a data item because an episode is tens of megabytes and a data item is
     * replicated to every connected node whether it wants it or not; see
     * [md.borisveriga.bpodcat.core.wearprotocol.WearPaths.EPISODE_AUDIO].
     */
    @Provides
    @Singleton
    fun providesChannelClient(@ApplicationContext context: Context): ChannelClient =
        Wearable.getChannelClient(context)
}

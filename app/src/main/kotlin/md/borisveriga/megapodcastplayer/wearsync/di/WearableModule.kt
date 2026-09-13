package md.borisveriga.megapodcastplayer.wearsync.di

import android.content.Context
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
 * Provides the phone's handles on the Wearable Data Layer.
 *
 * The phone mostly publishes state and lets the watch do the asking: its commands arrive through
 * [md.borisveriga.megapodcastplayer.wearsync.WearCommandService] rather than through a client. It
 * initiates one thing of its own: the end-of-episode bell, a message — the only thing the phone has
 * to say that the watch could not have asked for, because the point of it is to arrive while nobody
 * is looking at either device.
 *
 * The [NodeClient] serves both directions: it is how
 * [md.borisveriga.megapodcastplayer.wearsync.WearSenderVerifier] answers "is this sender a node we
 * are actually paired with" before a command reaches the player, and how
 * [md.borisveriga.megapodcastplayer.wearsync.WatchBellSender] finds the watches to buzz.
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
     * The message client, which carries the end-of-episode bell to the watch.
     *
     * A message rather than a data item because a bell is an event; see
     * [md.borisveriga.megapodcastplayer.core.wearprotocol.WearPaths.BELL].
     */
    @Provides
    @Singleton
    fun providesMessageClient(@ApplicationContext context: Context): MessageClient =
        Wearable.getMessageClient(context)
}

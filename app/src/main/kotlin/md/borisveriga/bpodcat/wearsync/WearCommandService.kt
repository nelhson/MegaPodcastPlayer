package md.borisveriga.bpodcat.wearsync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.common.di.ApplicationScope
import md.borisveriga.bpodcat.core.wearprotocol.WearMessages
import md.borisveriga.bpodcat.core.wearprotocol.WearPaths

/**
 * Receives the watch's commands.
 *
 * Play Services starts this service — and with it the app's process — whenever a message arrives on
 * a path this app's manifest claims, which is what lets the watch control a phone whose app has not
 * been opened in days.
 *
 * @property executor applies the command to the player.
 * @property senderVerifier decides whether the sending node is one this phone is paired with.
 * @property publisher started here as well as from the application class, because this service may
 *   be what brought the process up.
 * @property libraryPublisher started here for the same reason: a watch opening the "copy to watch"
 *   list is often what wakes this process, and the list has to be current when it arrives.
 * @property scope application scope: [onMessageReceived] must return promptly, and the work it
 *   starts has to outlive the callback.
 */
@AndroidEntryPoint
class WearCommandService : WearableListenerService() {

    @Inject
    internal lateinit var executor: WearCommandExecutor

    @Inject
    internal lateinit var senderVerifier: WearSenderVerifier

    @Inject
    internal lateinit var publisher: NowPlayingPublisher

    @Inject
    internal lateinit var libraryPublisher: OfflineLibraryPublisher

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Idempotent; the application class normally wins the race, and it does not matter which.
        publisher.start()
        libraryPublisher.start()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearPaths.COMMAND) return
        // A payload this build cannot parse comes from a newer watch app; ignoring it is the whole
        // of the compatibility policy, and is preferable to crashing a Play Services callback.
        val command = WearMessages.decodeCommand(messageEvent.data) ?: return
        val sourceNodeId = messageEvent.sourceNodeId

        scope.launch {
            // Checked here rather than in onMessageReceived itself because reading the node list
            // suspends, and this callback must return promptly. Verifying before executing — not
            // after — is the point: an unverified command never reaches the player.
            if (senderVerifier.isTrusted(sourceNodeId)) {
                executor.execute(command, sourceNodeId)
            }
        }
    }
}

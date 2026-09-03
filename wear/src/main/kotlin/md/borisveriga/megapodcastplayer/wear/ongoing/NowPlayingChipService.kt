package md.borisveriga.megapodcastplayer.wear.ongoing

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearMessages
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearPaths

/**
 * Keeps the watch-face playback chip in step with the phone.
 *
 * This exists as a service rather than as something the screen drives because the point of the chip
 * is to be there when the app is *not* open — and the watch app's process is killed shortly after it
 * is backgrounded. Play Services starts this service when a data item arrives, so the chip stays
 * correct without anything of ours running in between.
 *
 * It deliberately holds no state. Every decision is made from the snapshot in hand; see
 * [shouldShowChip].
 *
 * It is also what keeps the tile and the complication current: both are drawn on demand by the
 * system and neither can notice a data item on its own, so this pushes them an update whenever one
 * lands. See [refreshWatchFaceSurfaces].
 */
class NowPlayingChipService : WearableListenerService() {

    private val notifications: NowPlayingNotifications by lazy { NowPlayingNotifications(this) }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Only the last event matters: they arrive in order, and the chip reflects the current
        // state rather than the history of how it got there.
        val latest = dataEvents.lastOrNull { it.dataItem.uri.path == WearPaths.NOW_PLAYING }
            ?: return

        if (latest.type == DataEvent.TYPE_DELETED) {
            notifications.clear()
            refreshWatchFaceSurfaces(this)
            return
        }

        notifications.update(snapshotFrom(latest))
        // The chip is not the only thing outside the app that draws this snapshot; see
        // refreshWatchFaceSurfaces for why the other two have to be told rather than noticing.
        refreshWatchFaceSurfaces(this)
    }

    /**
     * Clears the chip when the phone app stops being reachable.
     *
     * Without this, walking out of Bluetooth range mid-episode leaves a chip claiming playback
     * indefinitely: no further data item can arrive to correct it, so the last thing the watch heard
     * stays on screen as a lie. This is the one transition that has to be inferred rather than read.
     */
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.name != WearPaths.PHONE_CAPABILITY) return
        if (capabilityInfo.nodes.isEmpty()) {
            notifications.clear()
            refreshWatchFaceSurfaces(this)
        }
    }

    /**
     * Decodes the snapshot carried by a data event.
     *
     * @return null when the item has no payload, or one this build cannot read — which is how a
     *   watch survives meeting a phone running a newer version of the app.
     */
    private fun snapshotFrom(event: DataEvent): NowPlayingSnapshot? {
        val bytes = DataMapItem.fromDataItem(event.dataItem)
            .dataMap
            .getByteArray(WearPaths.PAYLOAD_KEY)
            ?: return null

        return WearMessages.decodeSnapshot(bytes)
    }
}

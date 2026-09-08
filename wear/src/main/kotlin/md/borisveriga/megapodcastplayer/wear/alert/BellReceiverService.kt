package md.borisveriga.megapodcastplayer.wear.alert

import android.os.VibrationEffect
import android.os.VibratorManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearPaths

/**
 * Buzzes the wrist when the phone's end-of-episode bell rings.
 *
 * The one thing the phone sends the watch that the watch did not ask for. It is a service rather
 * than anything the app owns for the same reason [md.borisveriga.megapodcastplayer.wear.ongoing.NowPlayingChipService]
 * is: the whole point of the bell is to arrive when nothing of ours is running — the wearer is
 * asleep and the watch app was killed hours ago — and Play Services starts this, and the process
 * with it, on delivery.
 *
 * Vibration only, and deliberately no notification. The phone's card already names the episode; the
 * watch's job here is to be the thing that is actually touching the person.
 */
class BellReceiverService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // A listener service is offered every message the phone sends, not only ours.
        if (messageEvent.path != WearPaths.BELL) return

        // Null on a device with no vibrator, which is not a case worth a fallback: there is nothing
        // else this service could do, and the phone has already made its own noise.
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, NO_REPEAT))
    }

    private companion object {

        /**
         * Buzz, pause, buzz, pause, buzz — as alternating off/on milliseconds.
         *
         * Long enough and repeated enough to be felt through sleep, which a single tap is not, and
         * still short enough to be over before the wearer has found the phone.
         */
        val PATTERN = longArrayOf(0L, 600L, 300L, 600L, 300L, 600L)

        /** Plays [PATTERN] once; see [VibrationEffect.createWaveform]. */
        const val NO_REPEAT = -1
    }
}

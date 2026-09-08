package md.borisveriga.megapodcastplayer.core.designsystem.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Reads the system's "remove animations" setting, and keeps reading it.
 *
 * Android exposes this as *Remove animations* in accessibility settings, and it writes
 * [Settings.Global.ANIMATOR_DURATION_SCALE] to zero. The platform honours it for its own
 * transitions and for `ValueAnimator`, but Compose's animation clock is not a `ValueAnimator` — so
 * an app that hand-rolls its motion, as this one does, keeps animating regardless unless it asks.
 *
 * This app has five loops that never stop on their own: the scrubber's travelling wave, the wavy
 * refresh hairline, the morphing loader, the now-playing bars and the watch's waveform. For a user
 * who turned animations off — most often because motion makes them ill, not because they dislike it
 * — a screen that keeps moving is not a stylistic disagreement. Every one of them consults
 * [MegaPodcastPlayerTheme.reduceMotion], which is fed from here.
 *
 * Observed rather than sampled once: the setting is changed in system settings, which means the app
 * is in the background when it changes and would otherwise come back still animating until its
 * process next died. The observer is registered for the composition's lifetime and unregistered
 * with it.
 *
 * @return true when animations should be suppressed.
 */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduce by remember(resolver) { mutableStateOf(resolver.animationsRemoved()) }

    DisposableEffect(resolver) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = resolver.animationsRemoved()
            }
        }
        resolver.registerContentObserver(uri, /* notifyForDescendants = */ false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return reduce
}

/** True when the animator duration scale is zero, which is how the platform spells "no animations". */
private fun ContentResolver.animationsRemoved(): Boolean =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

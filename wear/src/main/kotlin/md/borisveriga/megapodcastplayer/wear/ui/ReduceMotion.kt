package md.borisveriga.megapodcastplayer.wear.ui

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
 * Whether the wearer has asked the system to stop animating things.
 *
 * The same reading as the phone's `LocalReduceMotion` in `:core:designsystem`, and deliberately a
 * second copy rather than a shared one: the watch is its own design — no artwork, a colour per
 * show, one scrolling list — and it does not depend on the phone's design system. Six lines of
 * duplication is cheaper than a Compose dependency crossing between two apps that agree on nothing
 * else visual.
 *
 * *Remove animations* writes [Settings.Global.ANIMATOR_DURATION_SCALE] to zero. Compose's animation
 * clock is not a `ValueAnimator` and ignores it, so the waveform would otherwise keep travelling on
 * a wrist whose owner asked it not to.
 *
 * @return true when animations should be suppressed.
 */
@Composable
fun rememberReduceMotion(): Boolean {
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

package md.borisveriga.megapodcastplayer.feature.player

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import kotlin.math.sqrt

/**
 * "I am still awake": a shake adds time to a running sleep timer.
 *
 * The gesture exists because of where the sleep timer is used. The phone is face down on a bedside
 * table, the screen is off, and the person using it has one decision left to make — keep going or
 * not. Unlocking, finding the app and opening a sheet is not a thing anyone does at that point;
 * picking the phone up and shaking it is.
 *
 * Registered only while a countdown is actually running, which the caller decides. The accelerometer
 * is cheap but not free, and a listener attached for the whole life of the player would be a sensor
 * running through every episode anyone ever plays.
 *
 * `SENSOR_DELAY_UI` rather than a faster rate: a shake lasts a good fraction of a second, and the
 * slowest rate that can see one is the one that costs least.
 *
 * @param enabled whether to listen at all; false unregisters immediately.
 * @param onShake invoked once per shake, on the main thread.
 */
@Composable
fun ShakeToExtendEffect(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    // Read late, so a shake never calls a handler from a composition that has since gone.
    val currentOnShake by rememberUpdatedState(onShake)

    DisposableEffect(enabled, context) {
        if (!enabled) return@DisposableEffect onDispose { }

        val sensorManager = context.getSystemService<SensorManager>()
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // A device with no accelerometer is unusual and not an error: the sheet's own chips are
        // still there, and the hint about shaking is the only thing that is now untrue.
        if (sensorManager == null || accelerometer == null) return@DisposableEffect onDispose { }

        val listener = ShakeListener { currentOnShake() }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }
}

/**
 * Turns accelerometer samples into shakes.
 *
 * The rule is deliberately blunt: total acceleration well past gravity, twice, close together. Two
 * peaks rather than one is what separates a shake from setting the phone down, which is a single
 * spike of exactly the same magnitude — and a sleep timer that extended itself when the phone was
 * put on the table would be worse than no gesture at all.
 *
 * @property onShake called once per recognised shake.
 */
private class ShakeListener(private val onShake: () -> Unit) : SensorEventListener {

    /** When the first peak of the current candidate shake was seen, or zero for none. */
    private var firstPeakAtMs = 0L

    /** When the last shake fired, so one wobble is not read as three. */
    private var lastShakeAtMs = 0L

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values.let { Triple(it[0], it[1], it[2]) }
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gForce < SHAKE_G_FORCE) return

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastShakeAtMs < COOLDOWN_MS) return

        if (nowMs - firstPeakAtMs > PEAK_WINDOW_MS) {
            firstPeakAtMs = nowMs
            return
        }

        firstPeakAtMs = 0L
        lastShakeAtMs = nowMs
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Comfortably past the 1 g of a phone lying still, and past a firm tap on the table. */
        const val SHAKE_G_FORCE = 2.2f

        /** How long after one peak a second still counts as part of the same shake. */
        const val PEAK_WINDOW_MS = 600L

        /** How long after a shake the next one is ignored, so one wobble fires once. */
        const val COOLDOWN_MS = 3_000L
    }
}

package md.borisveriga.megapodcastplayer.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter

/**
 * Wear OS application entry point.
 *
 * Hosts a deliberately small Hilt graph: the watch owns no database and no HTTP stack, only the Data
 * Layer clients that talk to the phone.
 *
 * The watch records its own handled failures in its own device log, through the same
 * [CrashReporter] as the phone, and sends them nowhere. Its tile, chip and complication run with
 * the app closed and the phone asleep, where nothing on the phone would ever see a failure, so
 * `adb logcat` on the watch is where to look.
 *
 * @property crashReporter marks the process start in the log, next to the failures that follow.
 */
@HiltAndroidApp
class WearApplication : Application() {

    @Inject
    internal lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        crashReporter.log("Watch process started")
    }
}

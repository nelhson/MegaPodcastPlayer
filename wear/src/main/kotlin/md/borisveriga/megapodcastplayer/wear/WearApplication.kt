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
 * The watch reports its own crashes, to the same Firebase app as the phone — the two share an
 * application ID, so one `google-services.json` serves both and the `buildType` and process keys
 * are what tell the reports apart. That the watch reports separately is the point: its tile, chip
 * and complication run with the app closed and the phone asleep, where nothing on the phone would
 * ever see a failure.
 *
 * @property crashReporter constructed for its side effects: it sets the keys every report carries.
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

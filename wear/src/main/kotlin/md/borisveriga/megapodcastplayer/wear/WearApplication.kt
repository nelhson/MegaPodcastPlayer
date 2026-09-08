package md.borisveriga.megapodcastplayer.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.wear.data.WatchEpisodeStore

/**
 * Wear OS application entry point.
 *
 * Hosts a deliberately small Hilt graph: the watch owns no database and no HTTP stack, only the Data
 * Layer clients that talk to the phone, the files it has been sent, and a player for those.
 *
 * The watch reports its own crashes, to the same Firebase app as the phone — the two share an
 * application ID, so one `google-services.json` serves both and the `buildType` and process keys
 * are what tell the reports apart. That the watch reports separately is the point: it plays
 * episodes off the wrist, out of Bluetooth range, where nothing on the phone would ever see a
 * failure.
 *
 * @property episodeStore read here rather than by the first screen that wants it, because the tile,
 *   the complication and an arriving transfer can all reach the process before any screen does.
 * @property crashReporter constructed for its side effects: it sets the keys every report carries.
 * @property scope application scope: the read outlives whatever component started the process.
 */
@HiltAndroidApp
class WearApplication : Application() {

    @Inject
    internal lateinit var episodeStore: WatchEpisodeStore

    @Inject
    @ApplicationScope
    internal lateinit var scope: CoroutineScope

    @Inject
    internal lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        crashReporter.log("Watch process started")
        scope.launch { episodeStore.load() }
    }
}

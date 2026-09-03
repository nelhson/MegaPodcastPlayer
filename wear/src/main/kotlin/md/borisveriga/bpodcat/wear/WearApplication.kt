package md.borisveriga.bpodcat.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.common.di.ApplicationScope
import md.borisveriga.bpodcat.wear.data.WatchEpisodeStore

/**
 * Wear OS application entry point.
 *
 * Hosts a deliberately small Hilt graph: the watch owns no database and no HTTP stack, only the Data
 * Layer clients that talk to the phone, the files it has been sent, and a player for those.
 *
 * @property episodeStore read here rather than by the first screen that wants it, because the tile,
 *   the complication and an arriving transfer can all reach the process before any screen does.
 * @property scope application scope: the read outlives whatever component started the process.
 */
@HiltAndroidApp
class WearApplication : Application() {

    @Inject
    internal lateinit var episodeStore: WatchEpisodeStore

    @Inject
    @ApplicationScope
    internal lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope.launch { episodeStore.load() }
    }
}

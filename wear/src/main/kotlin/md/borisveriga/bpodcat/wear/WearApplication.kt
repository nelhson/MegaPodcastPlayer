package md.borisveriga.bpodcat.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Wear OS application entry point.
 *
 * Hosts a deliberately small Hilt graph: the watch owns no database, no HTTP stack and no player,
 * only the Data Layer client that talks to the phone.
 */
@HiltAndroidApp
class WearApplication : Application()

package md.borisveriga.megapodcastplayer.wear.ongoing

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import md.borisveriga.megapodcastplayer.wear.complication.NowPlayingComplicationService

/**
 * Nudges the complication on the watch face to redraw itself.
 *
 * A complication is pull-based: the system asks it for a picture when it feels like it, which for one
 * with no update period is essentially never. So it has to be told that the thing it draws has
 * changed, and the only event that knows is the phone's data item landing — which is what
 * [NowPlayingChipService] already wakes up for.
 *
 * This used to nudge a tile as well. The watch is one screen now, and the tile was a second, smaller
 * copy of it that had to be kept in step with the first.
 *
 * The call is not load-bearing. A complication this watch face does not use throws or does nothing;
 * either way the app, the chip and playback are unaffected, so a failure is logged and dropped rather
 * than propagated into a Play Services callback.
 *
 * @param context used to address the service.
 */
internal fun refreshWatchFaceSurfaces(context: Context) {
    runCatching {
        ComplicationDataSourceUpdateRequester
            .create(
                context,
                ComponentName(context, NowPlayingComplicationService::class.java),
            )
            .requestUpdateAll()
    }.onFailure { Log.w(TAG, "Could not refresh the complication", it) }
}

private const val TAG = "WatchFaceSurfaces"

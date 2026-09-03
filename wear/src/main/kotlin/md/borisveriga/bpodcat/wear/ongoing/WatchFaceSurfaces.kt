package md.borisveriga.bpodcat.wear.ongoing

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import md.borisveriga.bpodcat.wear.complication.NowPlayingComplicationService
import md.borisveriga.bpodcat.wear.tile.NowPlayingTileService

/**
 * Nudges the two surfaces outside the app to redraw themselves.
 *
 * The tile and the complication are pull-based: the system asks them for a picture when it feels
 * like it, which for a complication with no update period is essentially never. Both therefore have
 * to be told that the thing they draw has changed, and the only event that knows is the phone's data
 * item landing — which is what [NowPlayingChipService] already wakes up for.
 *
 * Neither call is load-bearing. A tile host that is not installed, or a complication this watch face
 * does not use, throws or does nothing; either way the app, the chip and playback are unaffected, so
 * a failure is logged and dropped rather than propagated into a Play Services callback.
 *
 * @param context used to address the two services.
 */
internal fun refreshWatchFaceSurfaces(context: Context) {
    runCatching {
        TileService.getUpdater(context).requestUpdate(NowPlayingTileService::class.java)
    }.onFailure { Log.w(TAG, "Could not refresh the tile", it) }

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

package md.borisveriga.bpodcat.wear.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.core.wearprotocol.WearCommand
import md.borisveriga.bpodcat.wear.R
import md.borisveriga.bpodcat.wear.data.PhonePlayerClient
import md.borisveriga.bpodcat.wear.data.extrapolatedPositionMs
import md.borisveriga.bpodcat.wear.ui.showAccentArgb

/**
 * The now-playing tile, one swipe from the watch face.
 *
 * The tile exists because the app does not: opening it takes a raise, a swipe and a wait for the
 * phone to answer, whereas a tile is already drawn by the time the wrist is up. It renders the same
 * data item the app reads, so it is correct without the phone being awake, and its three buttons
 * send the same commands the app's screen sends.
 *
 * Everything here is stateless. The system asks for a layout, this builds one from whatever the
 * Data Layer holds, and a tap arrives as the *next* request carrying an id — see [TileClicks].
 *
 * @property client the connection to the phone: the cached snapshot to draw, and the route for a
 *   button press.
 */
@AndroidEntryPoint
class NowPlayingTileService : TileService() {

    @Inject
    lateinit var client: PhonePlayerClient

    /**
     * Scope for the work behind a tile request.
     *
     * A tile request must return a future promptly, so the reads and the Bluetooth write happen
     * here; the scope is cancelled with the service, which cancels a request the system has already
     * stopped waiting for.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Builds the tile, first applying whatever was tapped.
     *
     * The command is sent *before* the snapshot is read, so the tile that comes back already
     * reflects the tap where it can. It usually cannot: the phone applies the command and
     * republishes, and that round trip is longer than this request, so the freshly read snapshot
     * still says what it said before. Hence [optimistically] — the button changes shape now, and
     * [refreshShortly] fetches the truth a moment later.
     */
    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val command = tileCommandFor(requestParams.currentState.lastClickableId)
        val delivered = command == null || client.send(command)
        if (command != null && delivered) refreshShortly()

        val snapshot = (client.cachedSnapshot() ?: NowPlayingSnapshot())
            .let { if (delivered && command != null) it.optimistically(command) else it }

        val layout = nowPlayingTileLayout(
            snapshot = snapshot,
            positionMs = extrapolatedPositionMs(snapshot, System.currentTimeMillis()),
            accentArgb = showAccentArgb(snapshot.showTitle),
            copy = tileCopy(this@NowPlayingTileService),
            commandFailed = !delivered,
            packageName = packageName,
        )

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            // Only while playing, and only every half minute: the progress bar is the one thing
            // here that goes stale on its own, and a tile that wakes the app up more often than
            // that costs more battery than the bar is worth.
            .setFreshnessIntervalMillis(
                if (snapshot.isPlaying) PLAYING_FRESHNESS_MS else NO_FRESHNESS,
            )
            .build()
    }

    /** The four glyphs the buttons draw; the layout refers to them by the names in [TileImages]. */
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future { tileResources() }

    /**
     * Asks the phone for fresh state when the tile comes into view.
     *
     * The cached data item may be minutes old, or may predate the phone being restarted. The reply
     * arrives as a data change, which
     * [md.borisveriga.bpodcat.wear.ongoing.NowPlayingChipService] turns into a tile update — so the
     * tile corrects itself a moment after it is looked at.
     */
    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        scope.launch { client.send(WearCommand.RequestState) }
    }

    /**
     * Re-asks for the tile once the phone has had time to answer.
     *
     * @return the job, so a cancelled service does not leave it waiting.
     */
    private fun refreshShortly(): Job = scope.launch {
        delay(COMMAND_SETTLE_MS)
        suspendRunCatching {
            getUpdater(this@NowPlayingTileService)
                .requestUpdate(NowPlayingTileService::class.java)
        }
    }

    private companion object {

        /**
         * Bumped when the images below change, never otherwise.
         *
         * The system caches a tile's resources against this string and only re-requests them when it
         * changes, so a fixed value is exactly right for a fixed set of drawables.
         */
        const val RESOURCES_VERSION = "1"

        /** How often a playing tile is rebuilt, so the progress bar keeps moving. */
        const val PLAYING_FRESHNESS_MS = 30_000L

        /** A paused tile has nothing that changes on its own. */
        const val NO_FRESHNESS = 0L

        /**
         * How long to leave the phone to apply a command and republish.
         *
         * Long enough for a Bluetooth round trip plus the player reacting, short enough that the
         * optimistic button has not had time to look wrong.
         */
        const val COMMAND_SETTLE_MS = 1_500L
    }
}

/**
 * The strings the tile draws, read from this module's resources.
 *
 * @param context any context; only resources are taken from it.
 */
internal fun tileCopy(context: Context): TileCopy = TileCopy(
    idleTitle = context.getString(R.string.watch_nothing_playing_title),
    idleBody = context.getString(R.string.watch_tile_idle_body),
    unreachable = context.getString(R.string.watch_command_failed),
    play = context.getString(R.string.watch_play),
    pause = context.getString(R.string.watch_pause),
    skipBack = context.getString(R.string.watch_tile_skip_back),
    skipForward = context.getString(R.string.watch_tile_skip_forward),
)

/**
 * The images the tile's buttons draw.
 *
 * Plain drawables rather than the app's numbered skip glyphs: a tile is built before the snapshot is
 * read in the general case, the numbered icons only exist for three of the intervals the phone
 * offers, and a tile that said "30" while the phone jumps 45 would be a small lie pressed daily.
 */
internal fun tileResources(): ResourceBuilders.Resources = ResourceBuilders.Resources.Builder()
    .setVersion("1")
    .addIdToImageMapping(TileImages.PLAY, drawableResource(R.drawable.ic_tile_play))
    .addIdToImageMapping(TileImages.PAUSE, drawableResource(R.drawable.ic_tile_pause))
    .addIdToImageMapping(TileImages.SKIP_BACK, drawableResource(R.drawable.ic_tile_skip_back))
    .addIdToImageMapping(
        TileImages.SKIP_FORWARD,
        drawableResource(R.drawable.ic_tile_skip_forward),
    )
    .build()

/** Wraps one drawable id as a tile image resource. */
private fun drawableResource(resourceId: Int): ResourceBuilders.ImageResource =
    ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder()
                .setResourceId(resourceId)
                .build(),
        )
        .build()

/**
 * Applies to the snapshot what the phone is about to do anyway.
 *
 * Only play/pause is guessed at, because only play/pause changes something the tile *draws* — the
 * skip buttons move a position that the next real snapshot will correct within a second, and
 * guessing at those would mean re-implementing the phone's skip intervals on the watch.
 *
 * @param command what was just sent.
 * @return the snapshot as it will very shortly be.
 */
internal fun NowPlayingSnapshot.optimistically(command: WearCommand): NowPlayingSnapshot =
    when (command) {
        WearCommand.TogglePlayPause -> copy(isPlaying = !isPlaying)
        else -> this
    }

/**
 * Bridges a coroutine to the [ListenableFuture] the tile contract expects.
 *
 * @param block the work to run.
 * @return a future that completes with its result, and cancels it if the system stops waiting.
 */
private fun <T> CoroutineScope.future(block: suspend () -> T): ListenableFuture<T> =
    CallbackToFutureAdapter.getFuture { completer ->
        val job = launch {
            suspendRunCatching { block() }
                .onSuccess { completer.set(it) }
                .onFailure { completer.setException(it) }
        }
        completer.addCancellationListener({ job.cancel() }, { it.run() })
        "tile-request"
    }

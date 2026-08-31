package md.borisveriga.bpodcat.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import md.borisveriga.bpodcat.core.designsystem.theme.Motion

/** The two places the player sheet rests. */
enum class PlayerSheetValue {
    /** A bar above the navigation bar. */
    Collapsed,

    /** The full-screen player. */
    Expanded,
}

/**
 * Remembers a [PlayerSheetState].
 *
 * Saved across configuration changes, so an expanded player survives a rotation or the Fold being
 * opened rather than snapping shut.
 *
 * @param initialValue where the sheet rests on first composition.
 * @return the state, hoisted by the app shell because the navigation bar has to react to it too.
 */
@Composable
fun rememberPlayerSheetState(
    initialValue: PlayerSheetValue = PlayerSheetValue.Collapsed,
): PlayerSheetState = rememberSaveable(saver = PlayerSheetState.Saver) {
    PlayerSheetState(initialValue)
}

/**
 * How far the player sheet is open, and how it gets there.
 *
 * The player is one surface with one number behind it rather than two destinations. Everything the
 * sheet draws — the artwork's size and position, which body is visible, the corner radius, the
 * height of the sheet itself — is a function of [progress], so the collapsed bar and the full
 * player are the same tree at 0 and 1 and every frame in between is a real state the user can stop
 * at. That is what makes the gesture reversible mid-drag, which a navigation transition can never
 * be.
 *
 * [progress] is a plain [Animatable] rather than an `AnchoredDraggableState`: the anchors here are
 * only ever 0 and 1, and what the sheet actually needs is the fraction, which an anchored state
 * would give in pixels to be converted back. This also keeps the whole interaction off
 * experimental API.
 *
 * @param initialValue where the sheet starts.
 */
@Stable
class PlayerSheetState internal constructor(initialValue: PlayerSheetValue) {

    private val expansion = Animatable(initialValue.fraction)

    /**
     * True once [settle] has picked an end and started animating there.
     *
     * Drag deltas are delivered on a different coroutine from the one that settles, so a delta
     * dispatched in the gesture's last frames can arrive *after* the settle has begun. Its
     * [Animatable.snapTo] would win the mutation mutex and cancel the settle's animation, parking
     * the sheet at a fraction while [targetValue] already said Expanded. The flag makes those late
     * deltas no-ops; [onDragStarted] clears it, so the next real gesture is free to drag again.
     */
    private var isSettling = false

    /**
     * Where the sheet is heading.
     *
     * Distinct from [progress] on purpose: the navigation bar and the back handler care about the
     * intent, not the frame. Reading `progress > 0.5f` instead would make the navigation bar
     * flicker back mid-drag.
     */
    var targetValue: PlayerSheetValue by mutableStateOf(initialValue)
        private set

    /** How open the sheet is, `0f` collapsed to `1f` expanded. */
    val progress: Float get() = expansion.value

    /** True while the sheet is expanded or on its way there. */
    val isExpanded: Boolean get() = targetValue == PlayerSheetValue.Expanded

    /** Opens the sheet. */
    suspend fun expand() {
        targetValue = PlayerSheetValue.Expanded
        expansion.animateTo(1f, Motion.smooth())
    }

    /** Closes the sheet. */
    suspend fun collapse() {
        targetValue = PlayerSheetValue.Collapsed
        expansion.animateTo(0f, Motion.smooth())
    }

    /**
     * Opens a new drag gesture, cancelling any settle the previous one committed to.
     *
     * Called from the drag handles' `onDragStarted`. Without it a sheet that has just been flung
     * would ignore the deltas of the gesture that grabbed it mid-animation.
     */
    fun onDragStarted() {
        isSettling = false
    }

    /**
     * Follows a drag.
     *
     * Late deltas are dropped once [settle] has committed — see [isSettling].
     *
     * @param deltaPx vertical movement since the last event; negative is upward, which opens.
     * @param sheetTravelPx how far the sheet moves between its two rest positions, so the same
     *   finger movement means the same fraction on any screen.
     */
    suspend fun dragBy(deltaPx: Float, sheetTravelPx: Float) {
        if (isSettling || sheetTravelPx <= 0f) return
        expansion.snapTo((expansion.value - deltaPx / sheetTravelPx).coerceIn(0f, 1f))
    }

    /**
     * Settles to whichever rest position the gesture asked for.
     *
     * A flick wins over position, which is what lets a short sharp upward swipe from the bar open
     * the player without dragging it most of the way there; below that speed the sheet goes
     * wherever it is nearer to.
     *
     * @param velocityPxPerSecond the drag's release velocity; negative is upward.
     * @param flingThresholdPxPerSecond the speed above which direction decides rather than position.
     */
    suspend fun settle(velocityPxPerSecond: Float, flingThresholdPxPerSecond: Float) {
        isSettling = true
        val expand = when {
            velocityPxPerSecond < -flingThresholdPxPerSecond -> true
            velocityPxPerSecond > flingThresholdPxPerSecond -> false
            else -> expansion.value > HALFWAY
        }
        if (expand) expand() else collapse()
    }

    /**
     * Moves the sheet without animating, which is how the predictive back gesture drives it.
     *
     * @param fraction where to put it, `0f`..`1f`.
     */
    suspend fun seekTo(fraction: Float) {
        expansion.snapTo(fraction.coerceIn(0f, 1f))
    }

    internal companion object {

        /** Restores only which end the sheet rested at; a half-open sheet is not a saved state. */
        val Saver: Saver<PlayerSheetState, Int> = Saver(
            save = { it.targetValue.ordinal },
            restore = { PlayerSheetState(PlayerSheetValue.entries[it]) },
        )

        private const val HALFWAY = 0.5f
    }
}

/** The sheet fraction this rest position corresponds to. */
private val PlayerSheetValue.fraction: Float
    get() = if (this == PlayerSheetValue.Expanded) 1f else 0f

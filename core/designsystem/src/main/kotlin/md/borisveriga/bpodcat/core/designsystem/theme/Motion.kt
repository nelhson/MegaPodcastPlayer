package md.borisveriga.bpodcat.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Named motion specs.
 *
 * Material 3 Expressive supplies a whole `MotionScheme` to the stock components, so buttons,
 * sheets and indicators get their springs for free. This object covers the animations we write
 * ourselves, so that "how does something spring in this app" is one answer rather than a
 * `spring()` call per site with whatever damping felt right that afternoon.
 *
 * The factories are generic because the same feel has to apply to a `Float` fraction, a `Dp`
 * size and a `Color` tint. Spring specs are cheap to construct, so a call per animation is fine.
 */
object Motion {

    /**
     * The house spring: a little overshoot, quick to settle.
     *
     * Used for anything the user directly caused — a press, a toggle, a row appearing.
     */
    fun <T> bouncy(): SpringSpec<T> = spring(
        dampingRatio = 0.62f,
        stiffness = 380f,
    )

    /**
     * No overshoot, still spring-driven.
     *
     * For anything large or position-critical, where a bounce would read as sloppy rather than
     * lively: the expanding player settling, a sheet snapping to an anchor.
     */
    fun <T> smooth(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * Deliberately loose and slow.
     *
     * Reserved for the scrubber wave amplitude, which should feel like it is breathing rather
     * than responding.
     */
    fun <T> lazy(): SpringSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = 90f,
    )

    /** Duration-based fade, for cross-fades where a spring has nothing meaningful to overshoot. */
    fun <T> fade(durationMillis: Int = FADE_DURATION_MS): FiniteAnimationSpec<T> = tween(
        durationMillis = durationMillis,
        easing = StandardEasing,
    )

    /** Material's standard easing curve, for the few places a tween is the right tool. */
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Default cross-fade duration. */
    const val FADE_DURATION_MS: Int = 200

    /** Duration of the enter/exit transition between navigation destinations. */
    const val NAV_TRANSITION_MS: Int = 300
}

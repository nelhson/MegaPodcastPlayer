package md.borisveriga.megapodcastplayer.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import md.borisveriga.megapodcastplayer.core.designsystem.theme.Motion

/**
 * How one screen gives way to the next.
 *
 * Navigation Compose's defaults fade destinations through each other, which says nothing about
 * where the new screen came from. These say it in the app's own motion vocabulary: a pushed screen
 * arrives from the end edge and the one behind it steps back a little; going back reverses exactly
 * that, so the gesture and the animation agree about which way the stack is running.
 *
 * The slide is a fraction of the width rather than the whole of it — [SLIDE_FRACTION] — because the
 * screens are already cross-fading. A full-width slide over a fade reads as two transitions at
 * once, and on the Fold's inner display it is a long way to travel.
 */
private const val SLIDE_FRACTION = 8

/** The new screen arriving on top of the current one. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(): EnterTransition =
    slideInHorizontally(animationSpec = Motion.smooth()) { width -> width / SLIDE_FRACTION } +
        fadeIn(animationSpec = Motion.fade())

/** The screen being covered; it recedes rather than leaving, because it is still on the stack. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(): ExitTransition =
    slideOutHorizontally(animationSpec = Motion.smooth()) { width -> -width / SLIDE_FRACTION } +
        fadeOut(animationSpec = Motion.fade())

/** The screen underneath coming back into view. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    slideInHorizontally(animationSpec = Motion.smooth()) { width -> -width / SLIDE_FRACTION } +
        fadeIn(animationSpec = Motion.fade())

/** The screen being dismissed, leaving the way it arrived. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    slideOutHorizontally(animationSpec = Motion.smooth()) { width -> width / SLIDE_FRACTION } +
        fadeOut(animationSpec = Motion.fade())

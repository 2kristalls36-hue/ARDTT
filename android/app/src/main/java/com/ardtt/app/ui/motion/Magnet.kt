package com.ardtt.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Magnet motion: the pull grows as the distance shrinks, so an element starts slowly
 * when far, speeds up as it nears its place and lands with a short click.
 */
/** «Магнит» (beta setting): off — elements glide to their place without the pull. */
val LocalMagnetEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

object Magnet {
    /** Accelerating curve: slow start, fastest at contact (like a force ~ 1/distance²). */
    val Easing: Easing = Easing { t -> t * t * t }

    /** Longer for longer trips, but sub-linearly: a far pull is slower overall, never sluggish. */
    fun durationMillis(distance: Float, fullRange: Float): Int =
        (150 + 430 * sqrt((distance / fullRange).coerceIn(0f, 1f))).toInt()
}

/**
 * Pulls this value to [target] with [Magnet.Easing], calls [onContact] at the moment it
 * lands, then recoils a hair and settles, the way a magnet clicks into place.
 */
suspend fun Animatable<Float, AnimationVector1D>.magnetTo(
    target: Float,
    fullRange: Float = 1f,
    onContact: () -> Unit = {},
    enabled: Boolean = true,
) {
    if (!enabled) {
        // No magnet: a plain settle, no click.
        animateTo(target, spring(dampingRatio = 0.85f, stiffness = 400f))
        return
    }
    val distance = abs(target - value)
    if (distance < 0.0005f * fullRange) {
        snapTo(target)
        return
    }
    val dir = if (target > value) 1f else -1f
    animateTo(target, tween(Magnet.durationMillis(distance, fullRange), easing = Magnet.Easing))
    onContact()
    // Click: a tiny bounce back against the motion, then home.
    val recoil = (0.012f * fullRange).coerceAtMost(distance * 0.3f)
    animateTo(target - dir * recoil, tween(55))
    animateTo(target, spring(dampingRatio = 0.5f, stiffness = 900f))
}

/**
 * List fling that ends with a magnet pull: after the fling slows down, the nearest item
 * is drawn flush with [position], accelerating into place instead of easing out.
 */
@Composable
fun rememberMagnetSnapFling(state: LazyListState, position: SnapPosition = SnapPosition.Start): androidx.compose.foundation.gestures.FlingBehavior {
    if (!LocalMagnetEnabled.current) return androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
    val decay = rememberSplineBasedDecay<Float>()
    return remember(state, position, decay) {
        snapFlingBehavior(
            snapLayoutInfoProvider = SnapLayoutInfoProvider(state, position),
            decayAnimationSpec = decay,
            snapAnimationSpec = tween(320, easing = Magnet.Easing),
        )
    }
}

/** A light tick when a magnet-snapped list comes to rest after a scroll. */
@Composable
fun MagnetSnapTick(state: LazyListState) {
    if (!LocalMagnetEnabled.current) return
    val haptics = LocalAppHaptics.current
    val scrolling = state.isScrollInProgress
    val was = remember { booleanArrayOf(false) }
    androidx.compose.runtime.LaunchedEffect(scrolling) {
        if (was[0] && !scrolling) haptics.perform(Haptic.Tick)
        was[0] = scrolling
    }
}

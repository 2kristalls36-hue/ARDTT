package com.ardtt.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/**
 * How [androidx.navigation.compose.NavHost] swaps tabs.
 *
 * Navigation Compose 2.8 drives [androidx.compose.animation.AnimatedContent]
 * with [androidx.compose.animation.core.SeekableTransitionState.animateTo],
 * which keeps both the leaving and entering destinations composed for the
 * default spring. [EnterTransition.None] / [ExitTransition.None] do **not**
 * hide the leaving screen — they leave it at full opacity — so two
 * transparent scaffolds stack for a beat. A 0 ms fadeOut actually hides it.
 */
internal object ArdttNavSwap {
    const val hideOutgoingWhileIncomingComposed = true
    const val durationMs = 0

    fun enter(): EnterTransition = fadeIn(animationSpec = tween(durationMs))

    fun exit(): ExitTransition = fadeOut(animationSpec = tween(durationMs))
}

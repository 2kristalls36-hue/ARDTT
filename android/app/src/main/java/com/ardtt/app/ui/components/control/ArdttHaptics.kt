package com.ardtt.app.ui.components.control

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/** Opt-in haptic feedback; a disabled instance is a no-op, not a null check. */
class ArdttHaptics(
    private val view: View,
    private val enabled: Boolean,
) {
    fun tick() {
        if (!enabled) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun success() {
        if (!enabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberArdttHaptics(enabled: Boolean): ArdttHaptics {
    val view = LocalView.current
    return remember(view, enabled) { ArdttHaptics(view, enabled) }
}

/**
 * Confirm pulse when [signal] flips from false to true.
 *
 * The first composition is skipped, so reopening a screen that is already
 * connected stays silent. Both tunnel screens carried their own copy of this
 * `previous` / `initialized` pair, once per signal.
 */
@Composable
fun RisingEdgeSuccessHaptic(haptics: ArdttHaptics, signal: Boolean) {
    var previous by remember { mutableStateOf(signal) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(signal) {
        if (initialized && !previous && signal) haptics.success()
        previous = signal
        initialized = true
    }
}

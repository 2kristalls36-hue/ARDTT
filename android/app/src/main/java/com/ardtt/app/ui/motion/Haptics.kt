package com.ardtt.app.ui.motion

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalView

/** Kinds of touch feedback, mapped to the richest system effect the device has. */
enum class Haptic {
    /** Light tick: a slider stop, a snapped row, a chip. */
    Tick,
    /** Press of a main button or a tab. */
    Click,
    /** A switch turned on / off. */
    ToggleOn, ToggleOff,
    /** Something succeeded: connected, link copied, transfer finished. */
    Confirm,
    /** Something failed: connection error, wrong input. */
    Reject,
    /** Long press opened something. */
    LongPress,
}

/**
 * App vibration, honoring the «Вибрация» setting (and the system touch feedback setting,
 * which View.performHapticFeedback respects by itself).
 */
class AppHaptics(private val view: View?, private val enabled: () -> Boolean) {
    fun perform(kind: Haptic) {
        if (!enabled()) return
        val v = view ?: return
        val sdk = Build.VERSION.SDK_INT
        val constant = when (kind) {
            Haptic.Tick -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Haptic.Click -> if (sdk >= 23) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.ToggleOn -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
            Haptic.ToggleOff -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
            Haptic.Confirm -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Haptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
        }
        v.performHapticFeedback(constant)
    }

    fun toggle(on: Boolean) = perform(if (on) Haptic.ToggleOn else Haptic.ToggleOff)

    companion object {
        val Off = AppHaptics(null) { false }
    }
}

val LocalAppHaptics = staticCompositionLocalOf { AppHaptics.Off }

@Composable
fun rememberAppHaptics(enabled: Boolean): AppHaptics {
    val view = LocalView.current
    val on = rememberUpdatedState(enabled)
    val haptics = remember(view) { AppHaptics(view) { on.value } }
    androidx.compose.runtime.DisposableEffect(haptics) {
        AppHapticsHub.current = haptics
        onDispose { if (AppHapticsHub.current === haptics) AppHapticsHub.current = null }
    }
    return haptics
}

/** The app window's haptics, for helpers outside composition (copy to clipboard). */
object AppHapticsHub {
    @Volatile var current: AppHaptics? = null
    fun perform(kind: Haptic) { current?.perform(kind) }
}

/** Compose's own haptics (used by text selection and our older code) routed through the setting. */
class SettingAwareHapticFeedback(private val app: AppHaptics) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        app.perform(if (hapticFeedbackType == HapticFeedbackType.LongPress) Haptic.LongPress else Haptic.Tick)
    }
}

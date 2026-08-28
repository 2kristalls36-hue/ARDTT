package com.nonamevpn.app.ui.telemetry

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.telemetry.TelemetryRecorder

private val RecordingRed = Color(0xFF8B0000)

@Composable
fun TelemetryRecordingOverlay(
    isRecording: Boolean,
    currentScreen: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val recorder = rememberRecorder(context)
    val alpha by rememberRecordingAlpha(isRecording)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isRecording) {
                        Modifier.pointerInput(currentScreen) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                recorder.logTouch(
                                    screen = currentScreen,
                                    x = down.position.x,
                                    y = down.position.y,
                                    elementId = null,
                                )
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        recorder.logScroll(currentScreen, delta.x, delta.y)
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }

        if (isRecording) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(width = 2.5.dp, color = RecordingRed.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun rememberRecordingAlpha(isRecording: Boolean): androidx.compose.runtime.State<Float> {
    val transition = rememberInfiniteTransition(label = "recording-border")
    return if (isRecording) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recording-alpha",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }
}

@Composable
private fun rememberRecorder(context: android.content.Context): TelemetryRecorder {
    return androidx.compose.runtime.remember { TelemetryRecorder.get(context) }
}

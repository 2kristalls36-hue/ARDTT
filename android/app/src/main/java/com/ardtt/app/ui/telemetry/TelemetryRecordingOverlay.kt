package com.ardtt.app.ui.telemetry

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ardtt.app.telemetry.TelemetryRecorder

// Bright red — visible on dark surfaces; original #8B0000 was nearly invisible.
private val RecordingRed = Color(0xFFFF3B30)

@Composable
fun TelemetryRecordingOverlay(
    isRecording: Boolean,
    currentScreen: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val recorder = rememberRecorder(context)

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
    }
}

@Composable
fun RecordingBorderOverlay(
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isRecording) return
    val alpha by rememberRecordingAlpha(isRecording)
    RecordingBorder(
        alpha = alpha,
        modifier = modifier
            .fillMaxSize()
            .zIndex(1000f),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RecordingBorder(
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val borderColor = RecordingRed.copy(alpha = alpha)
    val strokeWidth = 6.dp

  // Pass touches through to the UI below; only draw the frame.
    Box(
        modifier = modifier.pointerInteropFilter { false },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            drawRect(
                color = borderColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokePx, size.height - strokePx),
                style = Stroke(width = strokePx),
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
            targetValue = 0.72f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
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

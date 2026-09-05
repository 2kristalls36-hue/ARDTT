package com.ardtt.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttShapes
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

object AdminUnlockGesture {
    const val COMMIT_THRESHOLD = 0.88f
    const val HINT_THRESHOLD = 0.08f

    fun shouldCommit(progress: Float): Boolean = progress >= COMMIT_THRESHOLD

    fun shouldHintIncomplete(progress: Float): Boolean =
        progress >= HINT_THRESHOLD && progress < COMMIT_THRESHOLD
}

@Composable
fun AdminUnlockSlider(
    onUnlocked: () -> Unit,
    onIncomplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var committed by remember { mutableStateOf(false) }
    val onUnlockedState = rememberUpdatedState(onUnlocked)
    val onIncompleteState = rememberUpdatedState(onIncomplete)
    val colors = MaterialTheme.colorScheme
    val trackHeight = 56.dp
    val thumbSize = 48.dp
    val inset = 4.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(ArdttShapes.Card)
            .background(colors.primaryContainer)
            .semantics {
                contentDescription = "Ползунок режима администратора. Перетащите вправо до конца."
            },
    ) {
        val maxOffsetPx = with(density) {
            (maxWidth - inset - inset - thumbSize).toPx().coerceAtLeast(1f)
        }
        val progress = (offset.value / maxOffsetPx).coerceIn(0f, 1f)
        val dragState = rememberDraggableState { delta ->
            if (committed) return@rememberDraggableState
            val next = (offset.value + delta).coerceIn(0f, maxOffsetPx)
            scope.launch { offset.snapTo(next) }
        }
        val fillPx = (offset.value + with(density) { (inset + thumbSize).toPx() })
            .coerceAtMost(constraints.maxWidth.toFloat())

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(with(density) { fillPx.toDp() })
                .clip(ArdttShapes.Card)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            colors.primary.copy(alpha = 0.22f),
                            colors.primary.copy(alpha = 0.14f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Text(
            "Перетащите вправо",
            modifier = Modifier.align(Alignment.Center),
            color = colors.onPrimaryContainer.copy(
                alpha = (1f - progress * 1.15f).coerceIn(0.12f, 1f),
            ),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = inset)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onPrimary,
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = !committed,
                    onDragStopped = {
                        if (committed) return@draggable
                        scope.launch {
                            val ratio = offset.value / maxOffsetPx
                            if (AdminUnlockGesture.shouldCommit(ratio)) {
                                offset.animateTo(maxOffsetPx, tween(140))
                                committed = true
                                onUnlockedState.value()
                            } else {
                                val moved = AdminUnlockGesture.shouldHintIncomplete(ratio)
                                offset.animateTo(0f, tween(200))
                                if (moved) onIncompleteState.value()
                            }
                        }
                    },
                ),
        )
    }
}

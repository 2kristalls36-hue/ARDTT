package com.ardtt.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

object AdminUnlockGesture {
    const val COMMIT_THRESHOLD = 0.88f
    const val HINT_THRESHOLD = 0.08f

    fun shouldCommit(progress: Float): Boolean = progress >= COMMIT_THRESHOLD

    fun shouldHintIncomplete(progress: Float): Boolean =
        progress >= HINT_THRESHOLD && progress < COMMIT_THRESHOLD

    /** Hardware keys that stand in for the drag when the track has focus. */
    fun keyCommits(key: Key): Boolean =
        key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter
}

internal object AdminUnlockCopy {
    const val TRACK = "Перетащите вправо"
    const val DESCRIPTION =
        "Ползунок режима администратора. Перетащите вправо до конца или используйте действие «Активировать»."
    const val ACTIVATE = "Активировать режим администратора"
}

private object AdminUnlockDefaults {
    /** Same height as a primary button, so the gate reads as a control. */
    val TrackHeight = ArdttSize.Button
    val ThumbSize = ArdttSize.TouchTarget
    val Inset = ArdttSpacing.Tiny

    /** Fill gradient behind the thumb: primary at two low steps to transparent. */
    const val FillStartAlpha = ArdttAlpha.Outline
    const val FillMidAlpha = 0.14f

    /** The track caption fades a little faster than the thumb travels. */
    const val CaptionFadeRate = 1.15f
    const val CaptionMinAlpha = 0.12f
}

/**
 * Deliberate drag gate into admin mode.
 *
 * The drag is the primary path; accessibility services get a custom action
 * and a focused track commits on Enter / DPAD-center, so the mode is not
 * locked behind a gesture only.
 */
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(AdminUnlockDefaults.TrackHeight)
            .clip(ArdttShapes.Card)
            .background(colors.primaryContainer),
    ) {
        val maxOffsetPx = with(density) {
            (maxWidth - AdminUnlockDefaults.Inset * 2 - AdminUnlockDefaults.ThumbSize)
                .toPx()
                .coerceAtLeast(1f)
        }
        val progress = (offset.value / maxOffsetPx).coerceIn(0f, 1f)

        fun commit() {
            if (committed) return
            committed = true
            scope.launch {
                offset.animateTo(maxOffsetPx, tween(ArdttMotion.Quick))
                onUnlockedState.value()
            }
        }

        val dragState = rememberDraggableState { delta ->
            if (committed) return@rememberDraggableState
            val next = (offset.value + delta).coerceIn(0f, maxOffsetPx)
            scope.launch { offset.snapTo(next) }
        }
        val fillPx = (offset.value + with(density) { (AdminUnlockDefaults.Inset + AdminUnlockDefaults.ThumbSize).toPx() })
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
                            colors.primary.copy(alpha = AdminUnlockDefaults.FillStartAlpha),
                            colors.primary.copy(alpha = AdminUnlockDefaults.FillMidAlpha),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Text(
            AdminUnlockCopy.TRACK,
            modifier = Modifier.align(Alignment.Center),
            color = colors.onPrimaryContainer.copy(
                alpha = (1f - progress * AdminUnlockDefaults.CaptionFadeRate)
                    .coerceIn(AdminUnlockDefaults.CaptionMinAlpha, 1f),
            ),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = AdminUnlockDefaults.Inset)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(AdminUnlockDefaults.ThumbSize)
                .clip(ArdttShapes.Pill)
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
                .semantics {
                    contentDescription = AdminUnlockCopy.DESCRIPTION
                    customActions = listOf(
                        CustomAccessibilityAction(AdminUnlockCopy.ACTIVATE) {
                            commit()
                            true
                        },
                    )
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && AdminUnlockGesture.keyCommits(event.key)) {
                        commit()
                        true
                    } else {
                        false
                    }
                }
                .focusable(enabled = !committed)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = !committed,
                    onDragStopped = {
                        if (committed) return@draggable
                        scope.launch {
                            val ratio = offset.value / maxOffsetPx
                            if (AdminUnlockGesture.shouldCommit(ratio)) {
                                commit()
                            } else {
                                val moved = AdminUnlockGesture.shouldHintIncomplete(ratio)
                                offset.animateTo(0f, tween(ArdttMotion.Fast))
                                if (moved) onIncompleteState.value()
                            }
                        }
                    },
                ),
        )
    }
}

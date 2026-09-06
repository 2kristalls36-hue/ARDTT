package com.ardtt.app.ui.tunnel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ardtt.app.R
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class FlightAssetSpec(
    val resId: Int,
    val sizeDp: Int,
    val startXFrac: Float,
    val startYFrac: Float,
    val anchorXFrac: Float,
    val anchorYFrac: Float,
    val orbitRadiusXFrac: Float,
    val orbitRadiusYFrac: Float,
    val orbitDurationMs: Int,
    val delayMs: Long,
    val phaseRad: Float,
    val windStrength: Float,
    val gustFreqMul: Float,
    val gustPhase: Float,
    val compensationStrength: Float,
    val dragLimitXFrac: Float,
    val dragLimitYFrac: Float,
    val centerBiasX: Float = 0f,
    val centerBiasY: Float = 0f,
)

internal data class FlightPose(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val alpha: Float,
)

/** Hover pose. Orbit is a function of time so Compose can skip recomposing the sprite. */
internal fun flightPose(
    spec: FlightAssetSpec,
    elapsedSec: Float,
    sceneWidthPx: Float,
    sceneHeightPx: Float,
    arrivalProgress: Float,
    orbitBlend: Float,
    blowAwayProgress: Float,
    dragDx: Float,
    dragDy: Float,
): FlightPose {
    val periodSec = (spec.orbitDurationMs / 1000f).coerceAtLeast(0.001f)
    val orbit = ((elapsedSec / periodSec) % 1f) * (Math.PI * 2.0).toFloat()
    val xFrac = spec.startXFrac + (spec.anchorXFrac - spec.startXFrac) * arrivalProgress
    val yFrac = spec.startYFrac + (spec.anchorYFrac - spec.startYFrac) * arrivalProgress
    val base = orbit + spec.phaseRad
    val windCarrier = sin((base * spec.gustFreqMul + spec.gustPhase).toDouble()).toFloat()
    val xPrimary = sin(base.toDouble()).toFloat()
    val xCompensation = sin((base * 2f + 0.9f).toDouble()).toFloat()
    val xMicro = sin((base * 3f + 1.6f).toDouble()).toFloat()
    val yPrimary = sin((base + 1.2f).toDouble()).toFloat()
    val yCompensation = sin((base * 2f + 0.35f).toDouble()).toFloat()
    val yMicro = sin((base * 3f + 2.1f).toDouble()).toFloat()
    val windAmp = ((0.78f + 0.22f * windCarrier) * spec.windStrength).coerceAtLeast(0.05f) * orbitBlend
    val comp = spec.compensationStrength.coerceIn(0.05f, 0.45f)
    val micro = (0.10f + comp * 0.35f).coerceAtMost(0.22f)
    val primary = (1f - comp - micro).coerceAtLeast(0.45f)
    val orbitX = (xPrimary * primary + xCompensation * comp + xMicro * micro) *
        (sceneWidthPx * spec.orbitRadiusXFrac) * windAmp
    val orbitY = (yPrimary * (primary - 0.06f).coerceAtLeast(0.38f) + yCompensation * (comp + 0.04f) + yMicro * micro) *
        (sceneHeightPx * spec.orbitRadiusYFrac) * windAmp
    val wobbleRotation = (
        sin((base + 0.2f).toDouble()).toFloat() * 0.9f +
            sin((base * 2f + 1.4f).toDouble()).toFloat() * 0.35f
        ) * orbitBlend
    val windKickX = -sceneWidthPx * (0.36f + 0.12f * spec.windStrength) * blowAwayProgress
    val windKickY = -sceneHeightPx * 0.10f * blowAwayProgress
    val alpha = ((0.22f + 0.78f * arrivalProgress) * (1f - blowAwayProgress * 0.98f)).coerceIn(0f, 1f)
    return FlightPose(
        x = xFrac * sceneWidthPx + orbitX + windKickX + dragDx,
        y = yFrac * sceneHeightPx + orbitY + windKickY + dragDy,
        rotation = wobbleRotation - 18f * blowAwayProgress,
        alpha = alpha,
    )
}

@Composable
internal fun WhitelistSkyAnimation(
    restartToken: Int,
    blowAway: Boolean,
    modifier: Modifier = Modifier,
) {
    val assets = remember {
        listOf(
            FlightAssetSpec(
                resId = R.drawable.tunnel_drone_far,
                sizeDp = 152,
                startXFrac = 0.40f,
                startYFrac = -0.66f,
                anchorXFrac = 0.37f,
                anchorYFrac = 0.24f,
                orbitRadiusXFrac = 0.027f,
                orbitRadiusYFrac = 0.021f,
                orbitDurationMs = 9_200,
                delayMs = 80L,
                phaseRad = 0.4f,
                windStrength = 1.26f,
                gustFreqMul = 0.92f,
                gustPhase = 0.25f,
                compensationStrength = 0.18f,
                dragLimitXFrac = 0.09f,
                dragLimitYFrac = 0.06f,
                centerBiasX = 0.000f,
                centerBiasY = -0.003f,
            ),
            FlightAssetSpec(
                resId = R.drawable.tunnel_drone_mid,
                sizeDp = 76,
                startXFrac = -0.42f,
                startYFrac = 0.20f,
                anchorXFrac = 0.13f,
                anchorYFrac = 0.18f,
                orbitRadiusXFrac = 0.023f,
                orbitRadiusYFrac = 0.017f,
                orbitDurationMs = 10_100,
                delayMs = 0L,
                phaseRad = 1.3f,
                windStrength = 1.02f,
                gustFreqMul = 1.18f,
                gustPhase = 1.1f,
                compensationStrength = 0.26f,
                dragLimitXFrac = 0.08f,
                dragLimitYFrac = 0.055f,
                centerBiasX = -0.002f,
                centerBiasY = -0.014f,
            ),
            FlightAssetSpec(
                resId = R.drawable.tunnel_drone_near,
                sizeDp = 49,
                startXFrac = 1.26f,
                startYFrac = 0.24f,
                anchorXFrac = 0.72f,
                anchorYFrac = 0.20f,
                orbitRadiusXFrac = 0.018f,
                orbitRadiusYFrac = 0.014f,
                orbitDurationMs = 11_200,
                delayMs = 140L,
                phaseRad = 2.2f,
                windStrength = 0.86f,
                gustFreqMul = 1.43f,
                gustPhase = 2.05f,
                compensationStrength = 0.34f,
                dragLimitXFrac = 0.065f,
                dragLimitYFrac = 0.05f,
                centerBiasX = 0.019f,
                centerBiasY = 0.021f,
            ),
        )
    }
    val elapsedSec = remember { Animatable(0f) }
    LaunchedEffect(restartToken) {
        elapsedSec.snapTo(0f)
        val startNs = withFrameNanos { it }
        while (true) {
            val nowNs = withFrameNanos { it }
            elapsedSec.snapTo(((nowNs - startNs).coerceAtLeast(0L)) / 1_000_000_000f)
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val sceneWidthPx = constraints.maxWidth.toFloat()
        val sceneHeightPx = constraints.maxHeight.toFloat()
        assets.forEachIndexed { index, spec ->
            AnimatedFlightAsset(
                spec = spec,
                index = index,
                restartToken = restartToken,
                blowAway = blowAway,
                sceneWidthPx = sceneWidthPx,
                sceneHeightPx = sceneHeightPx,
                elapsedSec = elapsedSec,
            )
        }
    }
}

@Composable
private fun AnimatedFlightAsset(
    spec: FlightAssetSpec,
    index: Int,
    restartToken: Int,
    blowAway: Boolean,
    sceneWidthPx: Float,
    sceneHeightPx: Float,
    elapsedSec: Animatable<Float, AnimationVector1D>,
) {
    val density = LocalDensity.current
    var launchStarted by remember(spec.resId, restartToken) { mutableStateOf(false) }
    var dragging by remember(spec.resId, restartToken) { mutableStateOf(false) }
    var rawDragDx by remember(spec.resId, restartToken) { mutableFloatStateOf(0f) }
    var rawDragDy by remember(spec.resId, restartToken) { mutableFloatStateOf(0f) }
    LaunchedEffect(spec.resId, restartToken) {
        kotlinx.coroutines.delay(spec.delayMs)
        launchStarted = true
    }
    val arrivalProgress by animateFloatAsState(
        targetValue = if (launchStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 4_200, easing = LinearOutSlowInEasing),
        label = "flight_arrival_$index",
    )
    val orbitBlend by animateFloatAsState(
        targetValue = if (arrivalProgress > 0.985f) 1f else 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "flight_orbit_blend_$index",
    )
    val blowAwayProgress by animateFloatAsState(
        targetValue = if (blowAway) 1f else 0f,
        animationSpec = tween(durationMillis = 980, easing = FastOutLinearInEasing),
        label = "flight_blow_away_$index",
    )
    val dragDx by animateFloatAsState(
        targetValue = if (dragging) rawDragDx else 0f,
        animationSpec = if (dragging) {
            tween(durationMillis = 45, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "flight_drag_dx_$index",
    )
    val dragDy by animateFloatAsState(
        targetValue = if (dragging) rawDragDy else 0f,
        animationSpec = if (dragging) {
            tween(durationMillis = 45, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "flight_drag_dy_$index",
    )

    val touchSizeDp = (spec.sizeDp * 1.35f).dp
    val imageSizePx = with(density) { spec.sizeDp.dp.toPx() }
    val spriteFixX = -spec.centerBiasX * imageSizePx
    val spriteFixY = -spec.centerBiasY * imageSizePx
    Box(
        modifier = Modifier
            .size(touchSizeDp)
            .offset {
                val pose = flightPose(
                    spec = spec,
                    elapsedSec = elapsedSec.value,
                    sceneWidthPx = sceneWidthPx,
                    sceneHeightPx = sceneHeightPx,
                    arrivalProgress = arrivalProgress,
                    orbitBlend = orbitBlend,
                    blowAwayProgress = blowAwayProgress,
                    dragDx = dragDx,
                    dragDy = dragDy,
                )
                IntOffset(pose.x.roundToInt(), pose.y.roundToInt())
            }
            .pointerInput(spec.resId, restartToken, blowAway, sceneWidthPx, sceneHeightPx) {
                val dragLimitX = sceneWidthPx * spec.dragLimitXFrac
                val dragLimitY = sceneHeightPx * spec.dragLimitYFrac
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        rawDragDx = 0f
                        rawDragDy = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        rawDragDx = 0f
                        rawDragDy = 0f
                    },
                ) { change, dragAmount ->
                    if (blowAway) return@detectDragGestures
                    change.consume()
                    rawDragDx = (rawDragDx + dragAmount.x).coerceIn(-dragLimitX, dragLimitX)
                    rawDragDy = (rawDragDy + dragAmount.y).coerceIn(-dragLimitY, dragLimitY)
                }
            }
            .graphicsLayer {
                val pose = flightPose(
                    spec = spec,
                    elapsedSec = elapsedSec.value,
                    sceneWidthPx = sceneWidthPx,
                    sceneHeightPx = sceneHeightPx,
                    arrivalProgress = arrivalProgress,
                    orbitBlend = orbitBlend,
                    blowAwayProgress = blowAwayProgress,
                    dragDx = dragDx,
                    dragDy = dragDy,
                )
                translationX = pose.x - pose.x.roundToInt()
                translationY = pose.y - pose.y.roundToInt()
                alpha = pose.alpha
                rotationZ = pose.rotation
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    translationX = spriteFixX
                    translationY = spriteFixY
                }
                .drawBehind {
                    val flightBlend = arrivalProgress.coerceIn(0f, 1f)
                    val glowColor = Color(0xFF66D8FF).copy(alpha = 0.10f + 0.08f * flightBlend)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor, Color.Transparent),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension * 0.56f,
                        ),
                        radius = size.minDimension * 0.56f,
                        center = Offset(size.width / 2f, size.height / 2f),
                    )
                },
        )
        Image(
            painter = painterResource(spec.resId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(spec.sizeDp.dp),
        )
    }
}

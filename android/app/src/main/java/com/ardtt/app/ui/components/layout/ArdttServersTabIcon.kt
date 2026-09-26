package com.ardtt.app.ui.components.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Material outlined Dns, split so the lamps can blink without changing the
 * chassis. Same 24 dp viewport, same filled contour as the other tab icons.
 */
internal val ArdttServersChassis: ImageVector by lazy {
    serversVector("ServersChassis") {
        serverRack(
            innerTop = 15f,
            innerBottom = 19f,
            outerTop = 13f,
            outerBottomIsAbsolute = false,
        )
        serverRack(
            innerTop = 5f,
            innerBottom = 9f,
            outerTop = 3f,
            outerBottomIsAbsolute = true,
        )
    }
}

/** The two indicator dots on [ArdttServersChassis]. */
internal val ArdttServersLights: ImageVector by lazy {
    serversVector("ServersLights") {
        lamp(centerY = 17f, startY = 18.5f, absoluteTop = false)
        lamp(centerY = 7f, startY = 8.5f, absoluteTop = true)
    }
}

@Composable
internal fun ArdttServersTabIcon(
    lightAlpha: Float,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val alpha = lightAlpha.coerceIn(0f, 1f)
    Box(modifier) {
        Icon(
            imageVector = ArdttServersChassis,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = tint,
        )
        if (alpha > 0f) {
            Icon(
                imageVector = ArdttServersLights,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                tint = tint.copy(alpha = tint.alpha * alpha),
            )
        }
    }
}

private fun serversVector(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black), pathBuilder = block).build()

/**
 * One outlined server unit from Icons.Outlined.Dns.
 * [outerBottomIsAbsolute] matches the top unit, which closes with `V4`
 * rather than a relative rise.
 */
private fun PathBuilder.serverRack(
    innerTop: Float,
    innerBottom: Float,
    outerTop: Float,
    outerBottomIsAbsolute: Boolean,
) {
    moveTo(19f, innerTop)
    verticalLineTo(innerBottom)
    horizontalLineTo(5f)
    verticalLineTo(innerTop)
    horizontalLineToRelative(14f)
    moveToRelative(1f, -2f)
    horizontalLineTo(4f)
    curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
    verticalLineToRelative(6f)
    curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
    horizontalLineToRelative(16f)
    curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
    if (outerBottomIsAbsolute) {
        verticalLineTo(outerTop + 1f)
    } else {
        verticalLineToRelative(-6f)
    }
    curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
    close()
}

private fun PathBuilder.lamp(centerY: Float, startY: Float, absoluteTop: Boolean) {
    moveTo(7f, startY)
    curveToRelative(-0.82f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
    if (absoluteTop) {
        reflectiveCurveTo(6.18f, centerY - 1.5f, 7f, centerY - 1.5f)
        reflectiveCurveToRelative(1.5f, 0.68f, 1.5f, 1.5f)
        reflectiveCurveTo(7.83f, startY, 7f, startY)
    } else {
        reflectiveCurveToRelative(0.68f, -1.5f, 1.5f, -1.5f)
        reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
        reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
    }
    close()
}

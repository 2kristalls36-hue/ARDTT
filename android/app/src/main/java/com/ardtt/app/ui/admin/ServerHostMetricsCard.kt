package com.ardtt.app.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

@Composable
internal fun ServerHostMetricsCard(
    host: ProvisionAdminApi.HostMetrics,
    modifier: Modifier = Modifier,
    title: String = HostMetricsCopy.SERVER,
    borderColor: Color? = null,
) {
    ArdttSectionCard(
        modifier = modifier.fillMaxWidth(),
        border = borderColor?.let { BorderStroke(ArdttSize.Contour, it) },
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = ArdttSpacing.Small),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ArdttLayout.CardSpacing),
        ) {
            hostMetricSpecs(host).forEach { spec ->
                HostMetricCell(
                    percent = spec.percent,
                    title = spec.title,
                    detail = spec.detail,
                    ringColor = hostMetricRingColor(spec.title, MaterialTheme.colorScheme),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun hostMetricRingColor(title: String, scheme: ColorScheme): Color = when (title) {
    HostMetricsCopy.CPU -> scheme.primary
    HostMetricsCopy.RAM -> scheme.tertiary
    else -> scheme.secondary
}

@Composable
private fun HostMetricCell(
    percent: Float,
    title: String,
    detail: String,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
    ) {
        HostMetricRing(
            percent = percent,
            color = ringColor,
            label = formatHostPercent(percent),
        )
        Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Hairline)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun HostMetricRing(
    percent: Float,
    color: Color,
    label: String,
    size: Dp = ArdttSize.Chip,
    stroke: Dp = ArdttSpacing.Tiny,
) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ArdttAlpha.Muted)
    val sweep = (percent.coerceIn(0f, 100f) / 100f) * 360f
    BoxWithRing(
        size = size,
        trackColor = track,
        progressColor = color,
        sweepDegrees = sweep,
        stroke = stroke,
        centerLabel = label,
    )
}

@Composable
private fun BoxWithRing(
    size: Dp,
    trackColor: Color,
    progressColor: Color,
    sweepDegrees: Float,
    stroke: Dp,
    centerLabel: String,
) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val diam = this.size.minDimension - strokePx
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            val arcSize = Size(diam, diam)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (sweepDegrees > 0.5f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweepDegrees,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            centerLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Stick between hop tiles (and between VPS 1 / VPS 2 resource cards). Decorative only. */
@Composable
internal fun HopRoleConnector(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ArdttSize.Icon)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(ArdttSize.Contour)
                .fillMaxHeight()
                .background(color, ArdttShapes.Pill),
        )
    }
}

package com.ardtt.app.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.theme.ArdttSpacing

@Composable
internal fun ServerHostMetricsCard(
    host: ProvisionAdminApi.HostMetrics,
    modifier: Modifier = Modifier,
) {
    ArdttSectionCard(modifier = modifier.fillMaxWidth()) {
        Text(
            "Ресурсы сервера",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = ArdttSpacing.Small),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HostMetricCell(
                percent = host.cpuPercent,
                title = "CPU",
                detail = formatHostCpuCores(host.cpuCores),
                ringColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            HostMetricCell(
                percent = host.memPercent,
                title = "RAM",
                detail = formatHostMemDetail(host),
                ringColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            HostMetricCell(
                percent = host.diskPercent,
                title = "HDD",
                detail = formatHostDiskDetail(host),
                ringColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
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
        modifier = modifier.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
    ) {
        HostMetricRing(
            percent = percent,
            color = ringColor,
            label = formatHostPercent(percent),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    size: Dp = 44.dp,
    stroke: Dp = 4.dp,
) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
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

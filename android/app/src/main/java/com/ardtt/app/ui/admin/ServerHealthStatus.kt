package com.ardtt.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.connectedStatusColor
import com.ardtt.app.ui.theme.warningStatusColor


@Composable
private fun pingLatencyContentColor(
    pingMs: Long,
    poor: Color,
): Color? = when (pingLatencyTier(pingMs)) {
    PingLatencyTier.Good -> connectedStatusColor()
    PingLatencyTier.Fair -> warningStatusColor()
    PingLatencyTier.Poor -> poor
    null -> null
}
@Composable
private fun serverPresenceColor(health: HealthUi?): Color = when (health) {
    is HealthUi.Online -> connectedStatusColor()
    HealthUi.Unreachable, HealthUi.NotInstalled -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}
@Composable
internal fun ServerPresenceLabel(health: HealthUi?) {
    val parts = healthStatusParts(health)
    Text(
        parts.presence,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = serverPresenceColor(health),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
@Composable
internal fun ServerHealthStatusRow(
    health: HealthUi?,
    expectedVersion: String,
) {
    val parts = healthStatusParts(health, expectedVersion)
    if (parts.deploy.isNullOrEmpty() && parts.pingLabel.isEmpty()) return
    val expected = effectiveExpectedVersion(health, expectedVersion)
    val deployColor = when (health) {
        is HealthUi.Online ->
            if (DeployBundle.isCurrent(health.deployVersion, expected)) {
                connectedStatusColor()
            } else {
                warningStatusColor()
            }
        else -> null
    }
    val pingColor = pingLatencyContentColor(parts.pingMs, MaterialTheme.colorScheme.error)
    val labelStyle = MaterialTheme.typography.labelSmall
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
    ) {
        Text(
            parts.deploy.orEmpty(),
            style = labelStyle,
            fontWeight = FontWeight.SemiBold,
            color = deployColor ?: Color.Transparent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        Text(
            parts.pingLabel,
            style = labelStyle,
            fontWeight = FontWeight.SemiBold,
            color = pingColor ?: Color.Transparent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

package com.ardtt.app.ui.components.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

/** Label column width that keeps tunnel status values on one axis. */
private val InlineLabelWidth = 148.dp

/**
 * Label on the left, value on the right — the tunnel status panel form.
 *
 * [pending] swaps the value for a small spinner so callers stop hand-rolling
 * that branch, and [leadingIcon] hosts the Cloudflare / provider glyphs.
 */
@Composable
fun ArdttInlineFactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    pending: Boolean = false,
    labelWidth: Dp = InlineLabelWidth,
    maxLines: Int = 2,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(labelWidth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(
                ArdttSpacing.TinyPlus,
                Alignment.End,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ArdttSize.SpinnerSmall),
                    strokeWidth = ArdttSize.Stroke,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                leadingIcon?.invoke()
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = valueColor,
                    textAlign = TextAlign.End,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Caption above a prominent value — the bottom-sheet statistic form.
 */
@Composable
fun ArdttStackedFactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    maxLines: Int = 2,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
    ) {
        ArdttFactLabel(label)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [ArdttStackedFactRow] whose value can be copied and shared. */
@Composable
fun ArdttCopyRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: (() -> Unit)? = null,
    maxLines: Int = 2,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
    ) {
        ArdttFactLabel(label)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать")
            }
            if (onShare != null) {
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Поделиться")
                }
            }
        }
    }
}

@Composable
private fun ArdttFactLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

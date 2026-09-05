package com.ardtt.app.ui.components.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

/** Widest a centered state block gets before its text wraps. */
private val StateBlockMaxWidth = 360.dp

/**
 * Centered "nothing here / something failed / still loading" block.
 *
 * Nine screens had their own version of this: different max widths, paddings
 * and typography for the same three states. Everything now goes through
 * [ArdttEmptyState], [ArdttLoadingState] and [ArdttErrorState].
 */
@Composable
fun ArdttEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ArdttSpacing.XXLarge),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = StateBlockMaxWidth)
                .padding(horizontal = ArdttSpacing.XXLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(ArdttSize.IconHero),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            action?.invoke()
        }
    }
}

@Composable
fun ArdttLoadingState(
    modifier: Modifier = Modifier,
    size: Dp = ArdttSize.SpinnerLarge,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ArdttSpacing.XXLarge),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(size))
    }
}

@Composable
fun ArdttErrorState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    hint: String? = null,
    retryText: String = "Повторить",
    onRetry: (() -> Unit)? = null,
) {
    ArdttEmptyState(
        title = title,
        modifier = modifier,
        description = listOfNotNull(description, hint).joinToString("\n\n").ifBlank { null },
        action = onRetry?.let {
            { OutlinedButton(onClick = it) { Text(retryText) } }
        },
    )
}

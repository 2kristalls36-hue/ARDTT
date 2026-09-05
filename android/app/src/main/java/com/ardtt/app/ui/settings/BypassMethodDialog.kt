package com.ardtt.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.components.surface.sectionCardContourBorder

@Composable
fun BypassMethodDialog(
    visible: Boolean,
    highlight: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlight) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "bypass_dialog_highlight",
    )
    ArdttDialog(
        title = "Метод обхода",
        onDismissRequest = onDismiss,
        dismissAction = ArdttDialogAction(
            text = "Закрыть",
            onClick = onDismiss,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = sectionCardContourBorder(
                alpha = ArdttSectionCardDefaults.ContourAlpha + 0.52f * highlightAlpha,
            ),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                CallHashSettingsContent(showHeader = false)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Text(
            "В этом окне можно выполнить авторизацию, создать код звонка через ВКонтакте или ввести его вручную.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

package com.ardtt.app.ui.components.control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ardtt.app.ui.theme.isDarkSurface
import com.ardtt.app.ui.theme.ArdttRadius
import com.ardtt.app.ui.components.surface.LiquidGlassPanel
import com.ardtt.app.ui.components.surface.LiquidGlass
import com.ardtt.app.ui.components.surface.LocalGlassBackdrop
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

/**
 * Floating search field that sits above the tab pill: translucent shell
 * while idle, solid surface while the keyboard is open.
 */
@Composable
fun ArdttSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardVisible: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск",
    /** Entry field instead of search: no magnifier, the keyboard action submits. */
    onSubmit: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val backdrop = LocalGlassBackdrop.current
    val tap = Modifier
        .fillMaxWidth()
        .height(ArdttBottomChrome.ButtonHeight)
        .semantics { contentDescription = "Поиск" }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    val fieldRow: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxSize().padding(horizontal = ArdttSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSubmit == null) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(ArdttSize.Icon), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(ArdttSpacing.Medium))
            }
            val style = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Search,
                    autoCorrectEnabled = onSubmit == null,
                    keyboardType = if (onSubmit != null) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboard?.hide() },
                    onDone = { onSubmit?.invoke() ?: keyboard?.hide() },
                ),
                modifier = Modifier.weight(1f).background(Color.Transparent).focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = style.copy(color = colors.onSurfaceVariant.copy(alpha = ArdttAlpha.Subtle)),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                ArdttButton(
                    onClick = { onValueChange("") },
                    variant = ArdttButtonVariant.Icon,
                    icon = Icons.Filled.Close,
                    contentDescription = "Очистить",
                    contentColor = colors.onSurfaceVariant,
                )
            }
        }
    }
    if (backdrop != null && LiquidGlass.supported) {
        // Same node with the keyboard open (only the veil thickens), so the field keeps focus.
        val dark = isDarkSurface()
        val veil by androidx.compose.animation.core.animateFloatAsState(
            if (keyboardVisible) 0.92f else backdrop.veil(dark), label = "veil",
        )
        LiquidGlassPanel(
            backdrop = backdrop,
            cornerRadius = ArdttRadius.Control,
            tint = colors.surface.copy(alpha = veil),
            dark = dark,
            blur = backdrop.frost(),
            modifier = modifier.then(tap),
        ) { fieldRow() }
        return
    }
    Surface(
        modifier = modifier.then(tap),
        shape = ArdttShapes.Control,
        color = if (keyboardVisible) colors.surface else ArdttFloatingShell.shellColor(),
        border = ArdttFloatingShell.shellBorder(),
        shadowElevation = ArdttElevation.None,
        tonalElevation = ArdttElevation.None,
    ) { fieldRow() }
}

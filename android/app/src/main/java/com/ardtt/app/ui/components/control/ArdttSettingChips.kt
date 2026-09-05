package com.ardtt.app.ui.components.control

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.ardtt.app.bypass.DialPath
import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.DialPathCopy
import com.ardtt.app.ui.HideIpCopy
import com.ardtt.app.ui.PathModeCopy
import com.ardtt.app.ui.ThemeModeCopy
import com.ardtt.app.ui.pathModeNeedsCallHash
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttSize

/**
 * Connection settings rendered as segmented chips.
 *
 * Every row below is a list of [ArdttChoice] fed to [ArdttChoiceChipRow], so
 * layout, spacing and selected styling are defined once.
 */

@Composable
fun PathModeChipRow(
    pathMode: String,
    hasCallHash: Boolean,
    enabled: Boolean,
    onSelect: (ConnPathMode) -> Unit,
    onNeedCallHash: () -> Unit,
    modifier: Modifier = Modifier,
    chipHeight: Dp = ArdttSize.Chip,
    coloredSelection: Boolean = false,
) {
    val bypassBlocked = pathModeNeedsCallHash(ConnPathMode.Bypass, hasCallHash)
    ArdttChoiceChipRow(
        choices = listOf(
            ArdttChoice(ConnPathMode.Auto, PathModeCopy.AUTO),
            ArdttChoice(
                value = ConnPathMode.Direct,
                label = PathModeCopy.DIRECT,
                accent = if (coloredSelection) ArdttColors.PathDirect else null,
            ),
            ArdttChoice(
                value = ConnPathMode.Bypass,
                label = PathModeCopy.BYPASS,
                accent = if (coloredSelection) ArdttColors.PathBypass else null,
                dimmed = !hasCallHash,
                onBlocked = if (bypassBlocked) onNeedCallHash else null,
            ),
        ),
        selected = ConnPathMode.fromSetting(pathMode),
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        chipHeight = chipHeight,
    )
}

@Composable
fun HideIpChipRow(
    hideIp: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chipHeight: Dp = ArdttSize.Chip,
) {
    ArdttChoiceChipRow(
        choices = listOf(
            ArdttChoice(false, HideIpCopy.SERVER_CHIP),
            ArdttChoice(true, HideIpCopy.HIDDEN_CHIP),
        ),
        selected = hideIp,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        chipHeight = chipHeight,
    )
}

@Composable
fun DialPathChipRow(
    dial: String,
    onSelect: (DialPath) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chipHeight: Dp = ArdttSize.Chip,
) {
    ArdttChoiceChipRow(
        choices = listOf(
            ArdttChoice(DialPath.Auto, DialPathCopy.AUTO),
            ArdttChoice(DialPath.VkCalls, DialPathCopy.VK_CALLS),
            ArdttChoice(DialPath.Legacy, DialPathCopy.LEGACY),
        ),
        selected = DialPath.fromSetting(dial),
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        chipHeight = chipHeight,
    )
}

@Composable
fun ThemeModeChipRow(
    themeMode: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chipHeight: Dp = ArdttSize.Chip,
) {
    ArdttChoiceChipRow(
        choices = ThemeModeCopy.OPTIONS.map { (value, label) -> ArdttChoice(value, label) },
        selected = AppSettingsRepository.normalizeThemeMode(themeMode),
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        chipHeight = chipHeight,
    )
}

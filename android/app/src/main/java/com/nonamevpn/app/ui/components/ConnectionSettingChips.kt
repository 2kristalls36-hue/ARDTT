package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.bypass.DialPath
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.HideIpCopy
import com.nonamevpn.app.ui.PathModeCopy
import com.nonamevpn.app.ui.pathModeNeedsCallHash
import com.nonamevpn.app.ui.theme.NvpnColors

@Composable
fun PathModeChipRow(
    pathMode: String,
    hasCallHash: Boolean,
    enabled: Boolean,
    onSelect: (ConnPathMode) -> Unit,
    onNeedCallHash: () -> Unit,
    modifier: Modifier = Modifier,
    chipHeight: Dp = 44.dp,
    coloredSelection: Boolean = false,
) {
    val selected = ConnPathMode.fromSetting(pathMode)
    SettingChipRow(modifier) {
        SettingChoiceChip(
            label = PathModeCopy.AUTO,
            selected = selected == ConnPathMode.Auto,
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect(ConnPathMode.Auto) },
        )
        SettingChoiceChip(
            label = PathModeCopy.DIRECT,
            selected = selected == ConnPathMode.Direct,
            enabled = enabled,
            height = chipHeight,
            selectedContainer = if (coloredSelection) NvpnColors.pathDirect else null,
            onClick = { onSelect(ConnPathMode.Direct) },
        )
        SettingChoiceChip(
            label = PathModeCopy.BYPASS,
            selected = selected == ConnPathMode.Bypass,
            enabled = enabled,
            dimmed = !hasCallHash,
            height = chipHeight,
            selectedContainer = if (coloredSelection) NvpnColors.pathBypass else null,
            onClick = {
                if (pathModeNeedsCallHash(ConnPathMode.Bypass, hasCallHash)) {
                    onNeedCallHash()
                } else {
                    onSelect(ConnPathMode.Bypass)
                }
            },
        )
    }
}

@Composable
fun HideIpChipRow(
    hideIp: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chipHeight: Dp = 44.dp,
) {
    SettingChipRow(modifier) {
        SettingChoiceChip(
            label = HideIpCopy.SERVER_CHIP,
            selected = !hideIp,
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect(false) },
        )
        SettingChoiceChip(
            label = HideIpCopy.HIDDEN_CHIP,
            selected = hideIp,
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect(true) },
        )
    }
}

@Composable
fun DialPathChipRow(
    dial: String,
    onSelect: (DialPath) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chipHeight: Dp = 44.dp,
) {
    val selected = DialPath.fromSetting(dial)
    SettingChipRow(modifier) {
        SettingChoiceChip(
            label = "Авто",
            selected = selected == DialPath.Auto,
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect(DialPath.Auto) },
        )
        SettingChoiceChip(
            label = "vkcalls",
            selected = selected == DialPath.VkCalls,
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect(DialPath.VkCalls) },
        )
        SettingChoiceChip(
            label = "Капча",
            selected = selected == DialPath.Legacy,
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect(DialPath.Legacy) },
        )
    }
}

@Composable
fun ThemeModeChipRow(
    themeMode: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chipHeight: Dp = 44.dp,
) {
    val selected = AppSettingsRepository.normalizeThemeMode(themeMode)
    SettingChipRow(modifier) {
        SettingChoiceChip(
            label = "Система",
            selected = selected == "system",
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect("system") },
        )
        SettingChoiceChip(
            label = "Светлая",
            selected = selected == "light",
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect("light") },
        )
        SettingChoiceChip(
            label = "Тёмная",
            selected = selected == "dark",
            enabled = enabled,
            height = chipHeight,
            onClick = { onSelect("dark") },
        )
    }
}

@Composable
private fun SettingChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun RowScope.SettingChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    dimmed: Boolean = false,
    selectedContainer: Color? = null,
    height: Dp = 44.dp,
) {
    ChoiceChipButton(
        label = label,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.weight(1f),
        selectedContainer = selectedContainer,
        dimmed = dimmed,
        height = height,
    )
}

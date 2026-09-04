package com.nonamevpn.app.ui.settings

/**
 * Which rows the «Оформление» card shows.
 * Theme / dynamic colors / haptics stay visible during a test recording:
 * hiding them left only «Уведомление» until process restart.
 */
internal data class SettingsAppearanceSections(
    val showThemeControls: Boolean,
    val showClassicLook: Boolean,
)

internal fun settingsAppearanceSections(
    admin: Boolean,
    recordingActive: Boolean,
): SettingsAppearanceSections {
    // recordingActive is intentionally unused: recordings must not hide theme.
    return SettingsAppearanceSections(
        showThemeControls = true,
        showClassicLook = !admin,
    )
}

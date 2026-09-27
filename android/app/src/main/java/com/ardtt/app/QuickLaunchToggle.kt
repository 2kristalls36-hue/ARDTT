package com.ardtt.app

import android.content.Context
import android.content.Intent
import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.settings.AppSettingsRepository
import kotlinx.coroutines.flow.first

internal enum class QuickLaunchOutcome {
    Disconnect,
    ConnectInPlace,
    NeedVpnConsent,
    MissingProfile,
}

internal data class QuickLaunchResult(
    val outcome: QuickLaunchOutcome,
    val vpnConsentIntent: Intent? = null,
)

internal fun quickLaunchOutcome(
    hasActiveProfile: Boolean,
    running: Boolean,
    vpnConsentRequired: Boolean,
): QuickLaunchOutcome = when {
    running -> QuickLaunchOutcome.Disconnect
    !hasActiveProfile -> QuickLaunchOutcome.MissingProfile
    vpnConsentRequired -> QuickLaunchOutcome.NeedVpnConsent
    else -> QuickLaunchOutcome.ConnectInPlace
}

/**
 * What a Quick Settings tap must do before onClick returns.
 * SystemUI unbinds the tile as soon as onClick returns. A coroutine started
 * after that is no longer allowed to start the foreground VPN service, so the
 * connect path hands the work to VpnTunnelService while the tile is still
 * bound. Consent is the only case that collapses the shade.
 */
internal enum class QsClickEffect {
    Disconnect,
    StartToggleService,
    OpenVpnConsent,
}

internal fun qsClickEffect(sessionUp: Boolean, vpnConsentRequired: Boolean): QsClickEffect = when {
    sessionUp -> QsClickEffect.Disconnect
    vpnConsentRequired -> QsClickEffect.OpenVpnConsent
    else -> QsClickEffect.StartToggleService
}

internal fun qsClickStartsForegroundService(effect: QsClickEffect): Boolean =
    effect == QsClickEffect.StartToggleService

/** Shade stays open unless the system VPN consent activity has to be shown. */
internal fun qsToggleCollapsesShade(outcome: QuickLaunchOutcome): Boolean =
    outcome == QuickLaunchOutcome.NeedVpnConsent

internal fun qsToggleStartsActivity(outcome: QuickLaunchOutcome): Boolean =
    outcome == QuickLaunchOutcome.NeedVpnConsent

/**
 * Load the saved profile and toggle the tunnel. Safe after process death:
 * [ConnectionManager] is created here and the catalog is read from disk.
 * Does not start an Activity — the caller decides whether VPN consent UI
 * is allowed to collapse the notification shade.
 */
internal suspend fun runQuickLaunchToggle(
    context: Context,
    vpnPrepare: () -> Intent?,
): QuickLaunchResult {
    val app = context.applicationContext
    val settings = AppSettingsRepository(app)
    val catalog = ProfileRepository(app).snapshot()
    val profile = catalog.active
    val conn = ConnectionManager.get(app)
    if (profile != null) {
        conn.updateProfile(profile)
        conn.setPathMode(ConnPathMode.fromSetting(settings.pathModeName.first()))
    }
    val running = widgetTunnelIsRunning(conn.ui.value.state)
    val consent = if (!running && profile != null) vpnPrepare() else null
    val outcome = quickLaunchOutcome(
        hasActiveProfile = profile != null,
        running = running,
        vpnConsentRequired = consent != null,
    )
    when (outcome) {
        QuickLaunchOutcome.Disconnect -> {
            conn.disconnect()
            TunnelWidgetProvider.pushFromConnection(app)
        }
        QuickLaunchOutcome.ConnectInPlace -> {
            conn.connectWhenReady()
            TunnelWidgetProvider.pushFromConnection(app)
        }
        QuickLaunchOutcome.NeedVpnConsent,
        QuickLaunchOutcome.MissingProfile -> Unit
    }
    return QuickLaunchResult(outcome, consent)
}

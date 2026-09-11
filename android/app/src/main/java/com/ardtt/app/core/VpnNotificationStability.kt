package com.ardtt.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.ardtt.app.R

/**
 * Keeps the ongoing VPN shade plate from flickering.
 *
 * SystemUI often redraws the plate when channels are deleted/recreated or when
 * an identical custom notification is posted every second. Channel setup belongs
 * outside the live update path, and [notify] should run only when content changes.
 */
internal object VpnNotificationChannels {
    const val SHADE = "ardtt_vpn_shade_v6"
    const val MIN = "ardtt_vpn_min_v6"

    private val legacyIds = listOf(
        "ardtt_tunnel",
        "ardtt_tunnel_min",
        "ardtt_vpn_shade_v1",
        "ardtt_vpn_min_v1",
        "ardtt_vpn_shade_v2",
        "ardtt_vpn_min_v2",
        "ardtt_vpn_shade_v3",
        "ardtt_vpn_min_v3",
        "ardtt_vpn_shade_v4",
        "ardtt_vpn_min_v4",
        "ardtt_vpn_shade_v5",
        "ardtt_vpn_min_v5",
    )

    @Volatile
    private var legacyPurged = false

    fun id(showInShade: Boolean): String = if (showInShade) SHADE else MIN

    fun ensure(context: Context, nm: NotificationManager, showInShade: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!legacyPurged) {
            legacyIds.forEach { id ->
                runCatching { nm.deleteNotificationChannel(id) }
            }
            legacyPurged = true
        }
        // Create both channels once so shade ↔ silent toggles do not thrash SystemUI.
        ensureChannel(
            context = context,
            nm = nm,
            id = SHADE,
            name = context.getString(R.string.notif_channel_tunnel),
            description = "Уведомление о состоянии подключения и команда остановки",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            publicLockscreen = true,
        )
        ensureChannel(
            context = context,
            nm = nm,
            id = MIN,
            name = context.getString(R.string.notif_channel_tunnel_min),
            description = "Служебная запись службы подключения. Система не позволяет скрыть её полностью.",
            importance = NotificationManager.IMPORTANCE_MIN,
            publicLockscreen = false,
        )
        // Touch the active channel last so OEMs that rebind on create still land on it.
        nm.getNotificationChannel(id(showInShade))
    }

    private fun ensureChannel(
        context: Context,
        nm: NotificationManager,
        id: String,
        name: String,
        description: String,
        importance: Int,
        publicLockscreen: Boolean,
    ) {
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(
            NotificationChannel(id, name, importance).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                this.description = description
                lockscreenVisibility = if (publicLockscreen) {
                    Notification.VISIBILITY_PUBLIC
                } else {
                    Notification.VISIBILITY_SECRET
                }
            },
        )
    }

    /** Test helper — reset purge flag between unit tests. */
    internal fun resetLegacyPurgeForTests() {
        legacyPurged = false
    }
}

/** Stable fingerprint of shade plate content — skip [notify] when unchanged. */
internal fun vpnNotificationFingerprint(
    showInShade: Boolean,
    title: String = "",
    ip: String = "",
    rates: String = "",
    statusText: String? = null,
    showWarpIcon: Boolean = false,
    showWhitelistIcon: Boolean = false,
    sessionStartedAtMs: Long = 0L,
    trustedWifiWaiting: Boolean = false,
): String {
    if (!showInShade) return "min"
    return buildString {
        append(title).append('\u0001')
        append(ip).append('\u0001')
        append(rates).append('\u0001')
        append(statusText.orEmpty()).append('\u0001')
        append(if (showWarpIcon) '1' else '0').append('\u0001')
        append(if (showWhitelistIcon) '1' else '0').append('\u0001')
        append(sessionStartedAtMs).append('\u0001')
        append(if (trustedWifiWaiting) '1' else '0')
    }
}

internal fun vpnNotificationShouldRepost(
    previousFingerprint: String?,
    nextFingerprint: String,
    force: Boolean,
): Boolean = force || previousFingerprint != nextFingerprint

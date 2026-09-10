package com.ardtt.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ardtt.app.MainActivity
import com.ardtt.app.R
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.tunnel.BypassBackend
import com.ardtt.app.tunnel.DirectBackend
import com.ardtt.app.tunnel.TunEstablisher
import com.ardtt.app.tunnel.TunnelBackend
import com.ardtt.app.tunnel.TunnelBackendState
import com.ardtt.app.tunnel.TunnelSessionHolder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Single VpnService for Path A (AWG) and Path B (RAW/WRAP).
 *
 * Resilience (WDTT-Plus style):
 * - Soft reconnect on Wi‑Fi/LTE handover
 * - Wake-from-Doze rescue after SCREEN_ON
 * - Path B zero-workers / dead-process watchdog
 * - Trusted Wi‑Fi pause/resume
 */
class VpnTunnelService : VpnService(), TunEstablisher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var backend: TunnelBackend? = null
    private var sessionJob: Job? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var trustedWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val activeNetworks = ConcurrentHashMap.newKeySet<Network>()
    @Volatile private var lastValidatedNetworkId: Long? = null
    @Volatile private var lastPreferredUnderlayHandle: Long? = null
    @Volatile private var stableNetworkWasLost = false
    @Volatile private var handoverPreviousNetworkId: Long? = null
    @Volatile private var pendingHandoverUnderlayChanged = false
    @Volatile private var stableNetworkReconnectPending = false
    @Volatile private var networkChangeJob: Job? = null
    @Volatile private var softRestartJob: Job? = null
    @Volatile private var userStopRequested = false
    @Volatile private var softRestartInProgress = false
    @Volatile private var backendEpoch: Int = 0
    @Volatile private var tunnelSessionActive = false
    @Volatile private var lastSoftRestartAtMs = 0L
    @Volatile private var softRestartCount = 0
    @Volatile private var lastTunIp: String? = null
    @Volatile private var lastTunDns: String? = null
    @Volatile private var lastTunMtu: Int = 0
    @Volatile private var lastTunUnderlayIdentity: String? = null
    @Volatile private var lastTunFilterFingerprint: String? = null
    /** Handover with a new Wi‑Fi/SIM: next Bypass launch must [establish] a fresh TUN. */
    @Volatile private var rebuildTunOnNextLaunch = false
    /** libclient kept in the VK call after Auto Bypass→Direct on Wi‑Fi. */
    private var parkedBypass: BypassBackend? = null
    private var parkedCallExpireJob: Job? = null
    @Volatile private var parkedCallEpoch: Long = 0L
    @Volatile private var activeCallEpoch: Long = 0L
    private fun recoveryPolicy(): TransportRecoveryPolicy =
        transportRecoveryPolicy(TunnelSessionHolder.config?.path ?: VpnPath.Direct)

    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var wakeRescueJob: Job? = null
    @Volatile private var wakeRecoveryGraceUntilMs = 0L
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var zeroWorkersSinceMs = 0L
    @Volatile private var processDeadSinceMs = 0L
    @Volatile private var lastHandoffAtMs = 0L
    @Volatile private var stableNetworkEvidenceSinceMs = 0L
    @Volatile private var deadDirectHandledAtMs = 0L
    @Volatile private var lastDirectHealthLogAtMs = 0L
    /** Bypass started before Android VALIDATED LTE — rebind once it does. */
    @Volatile private var rebindBypassWhenValidated = false
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var handoverProbeInProgress = false
    @Volatile private var pendingHandover: PendingHandoverEvent? = null
    @Volatile private var softRestartEpoch: Long = 0L
    @Volatile private var networkChangeEpoch: Long = 0L
    @Volatile private var lastDataSubId: Int = android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var dataSubReceiver: BroadcastReceiver? = null
    private var telephonyCallback: android.telephony.TelephonyCallback? = null
    private var notifLiveJob: Job? = null
    private var trustedWifiSettingsJob: Job? = null

    @Volatile private var trustedWifiWaiting = false
    @Volatile private var trustedWifiWaitingSsid = ""
    @Volatile private var trustedWifiPausedAtMs = 0L
    @Volatile private var trustedWifiEvalJob: Job? = null
    private val settingsRepo by lazy { AppSettingsRepository(applicationContext) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userStopRequested = true
                trustedWifiWaiting = false
                cancelAllRecovery()
                stopSession(keepService = false)
                ConnectionManager.getOrNull()?.onServiceStopped()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART_TRANSPORT -> {
                if ((tunnelSessionActive || trustedWifiWaiting) && !userStopRequested) {
                    if (trustedWifiWaiting) {
                        resumeFromTrustedWifi("manual restart")
                    } else {
                        val pathOverride = intent.getStringExtra(EXTRA_PATH)?.let { name ->
                            runCatching { VpnPath.valueOf(name) }.getOrNull()
                        }
                        requestSoftRestart(
                            reason = intent.getStringExtra(EXTRA_RESTART_REASON)
                                ?: "Запрошено переподключение транспорта",
                            force = true,
                            pathOverride = pathOverride,
                            rebuildTun = intent.getBooleanExtra(EXTRA_REBUILD_TUN, false),
                        )
                    }
                }
                return START_STICKY
            }
            ACTION_REFRESH_NOTIFICATION -> {
                val path = TunnelSessionHolder.config?.path ?: VpnPath.Direct
                // Re-promote foreground so channel switches (shade ↔ silent) apply immediately.
                startForegroundNotification(path, "refresh")
                return START_STICKY
            }
            ACTION_SESSION_CONTROL -> {
                sessionControlNetOpsDelta(intent)?.let { allowed ->
                    (backend as? BypassBackend)?.setNetOpsAllowed(allowed)
                    parkedBypass?.setNetOpsAllowed(allowed)
                }
                if (sessionControlShouldApplyDiscard(
                        intent.hasExtra(EXTRA_DISCARD_PARKED),
                        intent.getBooleanExtra(EXTRA_DISCARD_PARKED, false),
                        if (intent.hasExtra(EXTRA_CALL_EPOCH)) {
                            intent.getLongExtra(EXTRA_CALL_EPOCH, -1L)
                        } else {
                            null
                        },
                        parkedCallEpoch,
                    )
                ) {
                    discardParkedCall("session-control identity")
                }
                if (sessionControlShouldApplyDiscard(
                        intent.hasExtra(EXTRA_DISCARD_ACTIVE),
                        intent.getBooleanExtra(EXTRA_DISCARD_ACTIVE, false),
                        if (intent.hasExtra(EXTRA_CALL_EPOCH)) {
                            intent.getLongExtra(EXTRA_CALL_EPOCH, -1L)
                        } else {
                            null
                        },
                        activeCallEpoch,
                    )
                ) {
                    discardActiveBypass("session-control identity")
                }
                if (intent.hasExtra(EXTRA_NETWORK_HANDLE)) {
                    val handle = intent.getLongExtra(EXTRA_NETWORK_HANDLE, 0L)
                    val kind = intent.getStringExtra(EXTRA_NETWORK_KIND) ?: "unknown"
                    val scope = intent.getStringExtra(EXTRA_NETWORK_SCOPE) ?: NETWORK_SCOPE_ACTIVE
                    when (scope) {
                        NETWORK_SCOPE_PARKED -> parkedBypass?.updateNetwork(kind, handle)
                        else -> (backend as? BypassBackend)?.updateNetwork(kind, handle)
                    }
                }
                return if (tunnelSessionActive) START_STICKY else START_NOT_STICKY
            }
            ACTION_START, null -> {
                userStopRequested = false
                trustedWifiWaiting = false
                startSession()
            }
        }
        return START_STICKY
    }

    private fun startSession() {
        val config = TunnelSessionHolder.config
        val path = config?.path ?: VpnPath.Direct

        startForegroundNotification(path, getString(R.string.notif_running))
        startUnlockedSession(path)
    }

    private fun startUnlockedSession(path: VpnPath) {
        ConnectionManager.getOrNull()?.onServiceStarted(path)
        AppLog.v(TAG, "session start path=$path")

        tunnelSessionActive = true
        softRestartInProgress = false
        handoverProbeInProgress = false
        pendingHandover = null
        sessionStartedAtMs = System.currentTimeMillis()
        lastHandoffAtMs = sessionStartedAtMs
        lastDirectHealthLogAtMs = 0L
        TransportHealth.reset()
        VpnLiveStats.reset()
        lastPreferredUnderlayHandle = pickBestUnderlayNetwork(this)?.networkHandle
        setupNetworkCallback()
        setupTrustedWifiMonitoring()
        registerScreenReceiver()
        registerDataSubscriptionMonitor()
        startWatchdog()
        startNotifLiveUpdates()
        startTrustedWifiSettingsObserver()
        // WDTT Plus: check trusted SSID before bringing the tunnel up.
        scope.launch { startOrWaitForTrustedWifi(path) }
    }

    /**
     * Like WDTT Plus `startOrWaitForTrustedWifi`: if already on a whitelisted
     * Wi‑Fi, enter waiting without launching Direct/Bypass backends.
     */
    private suspend fun startOrWaitForTrustedWifi(path: VpnPath) {
        if (userStopRequested) return
        val (enabled, ssids) = runCatching { settingsRepo.trustedWifiSnapshot() }
            .getOrDefault(false to emptySet())
        var wifi = readConnectedWifiState(this)
        if (enabled && ssids.isNotEmpty() && wifi.connected && !wifi.ssidAvailable) {
            AppLog.v(TAG, "start: Wi‑Fi up but SSID unread — wait up to ${TRUSTED_WIFI_SSID_WAIT_MS}ms")
            val deadline = System.currentTimeMillis() + TRUSTED_WIFI_SSID_WAIT_MS
            while (System.currentTimeMillis() < deadline && !userStopRequested) {
                delay(TRUSTED_WIFI_SSID_RETRY_MS)
                wifi = readConnectedWifiState(this)
                if (wifi.ssidAvailable || !wifi.connected) break
            }
        }
        val enter = decideTrustedWifiTransition(
            enabled = enabled,
            tunnelRunning = true,
            waiting = false,
            wifi = wifi,
            trustedSsids = ssids,
        ) == TrustedWifiTransition.EnterWaiting
        if (enter) {
            val ssid = wifi.ssid.ifBlank { "доверенная Wi‑Fi" }
            AppLog.i(TAG, "start on trusted wifi — waiting ssid=$ssid")
            enterTrustedWifiWaiting(ssid)
            return
        }
        launchBackend(TunnelSessionHolder.config?.path ?: path, softRestart = false)
        scheduleTrustedWifiEvaluation(TRUSTED_WIFI_ENTER_DELAY_MS)
    }

    private fun launchBackend(requestedPath: VpnPath, softRestart: Boolean) {
        val config = TunnelSessionHolder.config
        if (config == null) {
            AppLog.e(TAG, "No session config")
            softRestartInProgress = false
            ConnectionManager.getOrNull()?.onTunnelFailed("Нет конфигурации сессии")
            stopSelf()
            return
        }
        val path = config.path
        if (path != requestedPath) {
            AppLog.v(TAG, "launchBackend holder=$path requested=$requestedPath")
        }

        val epoch = ++backendEpoch
        val currentUnderlayId = underlayIdentity(this)
        val underlayMoved = rebuildTunOnNextLaunch ||
            tunUnderlayChanged(lastTunUnderlayIdentity, currentUnderlayId)
        rebuildTunOnNextLaunch = false
        val reuseBypassTun = shouldReuseBypassTunOnSoftRestart(
            softRestart = softRestart,
            pathIsBypass = path == VpnPath.Bypass,
            currentBackendIsBypass = backend is BypassBackend,
            tunValid = tunStillValid(),
            underlayChanged = underlayMoved,
        )
        if (underlayMoved && path == VpnPath.Bypass) {
            AppLog.i(TAG, "rebuild Bypass TUN — underlay changed (SIM/Wi‑Fi)")
        }
        val currentBackend = backend
        val parkCall = currentBackend is BypassBackend &&
            path == VpnPath.Direct &&
            shouldParkBypassCall(VpnPath.Bypass, VpnPath.Direct)
        if (parkCall && currentBackend is BypassBackend) {
            AppLog.i(TAG, "Parking VK call while switching to Direct")
            parkBypassCall(currentBackend)
        } else {
            val keepParked = parkedBypass != null &&
                (path == VpnPath.Direct || path == VpnPath.Bypass)
            if (!keepParked) {
                discardParkedCall("launch ${path.name}")
            }
            if (reuseBypassTun && currentBackend is BypassBackend) {
                currentBackend.stopKeepingTun()
            } else {
                currentBackend?.stop()
            }
        }
        sessionJob?.cancel()
        sessionJob = null
        backend = null
        if (path == VpnPath.Bypass && parkedBypass?.isCallParked == true) {
            val parked = parkedBypass
            parkedBypass = null
            parked?.setParkedDeathHandler(null)
            if (parked != null) {
                AppLog.i(TAG, "Resuming parked RAW process (same VK call)")
                backend = parked
                activeCallEpoch = parkedCallEpoch
                parkedCallEpoch = 0L
                sessionJob = scope.launch {
                    val epochResume = epoch
                    val ok = parked.resumeParked(this@VpnTunnelService) { state ->
                        if (epochResume != backendEpoch) return@resumeParked
                        when (state) {
                            is TunnelBackendState.Running -> {
                                tunnelSessionActive = true
                                ConnectionManager.getOrNull()?.onTunnelRunning(VpnPath.Bypass)
                                updateNotification(
                                    VpnPath.Bypass,
                                    ConnectionManager.getOrNull()?.notificationRunningText()
                                        ?: getString(R.string.notif_running),
                                )
                            }
                            is TunnelBackendState.Failed -> {
                                if (!userStopRequested && !trustedWifiWaiting) {
                                    applyTunnelFailureAction(state.message)
                                }
                            }
                            else -> Unit
                        }
                    }
                    if (!ok && epochResume == backendEpoch && !userStopRequested) {
                        AppLog.w(TAG, "Parked RAW resume failed — rebuilding transport, same call")
                        parked.stop()
                        backend = null
                        applyTunnelFailureAction("Не удалось возобновить обход с прежним звонком")
                    }
                }
                return
            }
        }
        if (!reuseBypassTun) {
            forgetTun()
        } else {
            AppLog.i(TAG, "keeping Bypass TUN for transport restart fd=${tun?.fd}")
            bindTunToUnderlay()
        }
        TransportHealth.noteBackendStarted()

        val chosen: TunnelBackend = when (path) {
            VpnPath.Direct -> DirectBackend()
            VpnPath.Bypass -> BypassBackend()
        }
        backend = chosen
        activeCallEpoch = config.callEpoch

        val fd: ParcelFileDescriptor? = when (path) {
            VpnPath.Direct -> {
                val address = config.tunAddress.substringBefore('/').takeIf { it.isNotBlank() }
                    ?: config.profile?.direct?.address?.substringBefore('/')
                    ?: "10.8.0.2"
                val mtu = (config.profile?.direct?.mtu ?: DIRECT_TUN_MTU).coerceAtMost(DIRECT_TUN_MTU)
                val dns = config.profile?.direct?.dns?.firstOrNull() ?: "10.8.0.1"
                establishTun(address, dns, mtu).also { created ->
                    if (created == null) {
                        AppLog.e(TAG, "TUN establish failed")
                        softRestartInProgress = false
                        applyTunnelFailureAction(vpnPermissionDeniedHint(this))
                    } else {
                        AppLog.v(TAG, "TUN ok ip=$address mtu=$mtu soft=$softRestart")
                    }
                }
            }
            VpnPath.Bypass -> null
        }
        if (path == VpnPath.Direct && fd == null) return

        sessionJob = scope.launch {
            try {
                chosen.start(this@VpnTunnelService, fd, config) { state ->
                    if (epoch != backendEpoch) {
                        AppLog.w(TAG, "stale backend callback epoch=$epoch current=$backendEpoch state=$state")
                        return@start
                    }
                    Log.i(TAG, "backend state=$state soft=$softRestartInProgress")
                    when (state) {
                        is TunnelBackendState.Running -> {
                            AppLog.v(TAG, "Running path=$path")
                            softRestartInProgress = false
                            tunnelSessionActive = true
                            if (path == VpnPath.Bypass) {
                                discardParkedCall("Bypass running")
                            }
                            // Keep underlay id in sync after soft restart so the next
                            // Wi‑Fi/LTE change is HANDOVER, not a silent INITIAL.
                            pickBestUnderlyingNetwork()?.let {
                                lastValidatedNetworkId = it.networkHandle
                            }
                            ConnectionManager.getOrNull()?.onTunnelRunning(path)
                            updateNotification(
                                path,
                                ConnectionManager.getOrNull()?.notificationRunningText()
                                    ?: getString(R.string.notif_running),
                            )
                            scheduleTrustedWifiEvaluation(TRUSTED_WIFI_ENTER_DELAY_MS)
                            drainPendingHandover()
                        }
                        is TunnelBackendState.Failed -> {
                            if (userStopRequested || trustedWifiWaiting) {
                                AppLog.w(TAG, "Ignoring failure after stop/pause: ${state.message}")
                                return@start
                            }
                            AppLog.e(TAG, "Failed: ${state.message}")
                            softRestartInProgress = false
                            applyTunnelFailureAction(state.message)
                        }
                        is TunnelBackendState.Stopped -> Unit
                        is TunnelBackendState.Starting -> {
                            AppLog.v(TAG, if (softRestart) "Soft restart starting…" else "Starting…")
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    AppLog.v(TAG, "session cancelled (normal stop/restart)")
                    throw t
                }
                if (epoch != backendEpoch || userStopRequested || trustedWifiWaiting) {
                    AppLog.w(TAG, "Ignoring crash from stale/stop backend: ${t.message}")
                    return@launch
                }
                AppLog.e(TAG, "backend crash: ${t.message}")
                softRestartInProgress = false
                applyTunnelFailureAction(t.message ?: "tunnel crash")
            }
        }
    }

    private fun applyTunnelFailureAction(message: String) {
        when (ConnectionManager.getOrNull()?.onTunnelFailed(message) ?: TunnelFailureAction.Stop) {
            TunnelFailureAction.Ignore -> Unit
            TunnelFailureAction.SwitchToBypass -> {
                AppLog.i(TAG, "Auto fallback Direct → Bypass")
                launchBackend(VpnPath.Bypass, softRestart = true)
            }
            TunnelFailureAction.HoldForCallRecreate -> {
                AppLog.i(TAG, "Holding VPN while recreating VK call")
                softRestartInProgress = true
                tunnelSessionActive = true
                val path = TunnelSessionHolder.config?.path ?: VpnPath.Bypass
                updateNotification(path, "Обновление звонка…")
            }
            TunnelFailureAction.KeepRecovering -> {
                AppLog.i(TAG, "Holding VPN for recovery backoff")
                softRestartInProgress = false
                tunnelSessionActive = true
            }
            TunnelFailureAction.Stop -> stopSelf()
        }
    }

    /**
     * Auto: re-classify underlay and soft-restart (possibly switching Direct↔Bypass).
     * Forced Direct/Bypass: soft-restart same path unless there is no usable network.
     */
    private suspend fun runHandoverProbeAndRestart(
        reason: String,
        underlayChanged: Boolean = false,
    ) {
        if (!tunnelSessionActive || userStopRequested || trustedWifiWaiting) return
        if (shouldDeferHandoverProbe(handoverProbeInProgress, softRestartInProgress) ||
            ConnectionManager.getOrNull()?.isRecoveryInFlight() == true
        ) {
            pendingHandover = mergePendingHandover(
                pendingHandover,
                PendingHandoverEvent(
                    reason = reason,
                    underlayChanged = underlayChanged || pendingHandoverUnderlayChanged,
                    previousNetworkId = handoverPreviousNetworkId,
                    evidenceSinceMs = System.currentTimeMillis(),
                    generation = networkChangeEpoch,
                    rebuildTun = underlayChanged,
                ),
            )
            AppLog.v(TAG, "handover queued ($reason) — probe/restart busy")
            return
        }
        handoverProbeInProgress = true
        try {
            val underlay = pickBestUnderlayNetwork(this) ?: pickBestUnderlyingNetwork()
            val decision = ConnectionManager.getOrNull()
                ?.decideNetworkHandover(underlay, underlayChanged)
                ?: NetworkHandoverDecision.SoftRestartSamePath
            when (decision) {
                NetworkHandoverDecision.NoAction -> {
                    softRestartInProgress = false
                    AppLog.v(
                        TAG,
                        "handover: no action underlayChanged=$underlayChanged ($reason)",
                    )
                }
                is NetworkHandoverDecision.SwitchPath -> {
                    AppLog.v(TAG, "handover path switch → ${decision.path}")
                    requestSoftRestart(
                        reason = "[СЕТЬ] $reason → ${decision.path}",
                        force = true,
                        pathOverride = decision.path,
                        rebuildTun = underlayChanged,
                    )
                }
                NetworkHandoverDecision.SoftRestartSamePath -> {
                    requestSoftRestart(
                        reason = "[СЕТЬ] $reason",
                        force = true,
                        rebuildTun = underlayChanged,
                    )
                }
                NetworkHandoverDecision.HoldWaitForNetwork -> {
                    AppLog.v(TAG, "handover hold — wait for underlay ($reason)")
                    val path = TunnelSessionHolder.config?.path ?: VpnPath.Direct
                    updateNotification(path, "Ожидание сети…")
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            softRestartInProgress = false
            AppLog.e(TAG, "handover probe failed: ${t.message}")
        } finally {
            handoverProbeInProgress = false
        }
    }

    private fun drainPendingHandover() {
        if (userStopRequested || trustedWifiWaiting || !tunnelSessionActive) return
        val pending = pendingHandover ?: return
        if (shouldDeferHandoverProbe(handoverProbeInProgress, softRestartInProgress)) return
        pendingHandover = null
        AppLog.v(TAG, "drain queued handover: ${pending.reason} underlayChanged=${pending.underlayChanged}")
        scheduleUnderlyingNetworkReconnect(
            reason = pending.reason,
            previousNetworkId = pending.previousNetworkId,
            evidenceSinceMs = pending.evidenceSinceMs,
            stickyUnderlayChanged = pending.underlayChanged,
        )
    }

    private fun requestSoftRestart(
        reason: String,
        force: Boolean = false,
        pathOverride: VpnPath? = null,
        rebuildTun: Boolean = false,
    ) {
        if ((!tunnelSessionActive && !trustedWifiWaiting) || userStopRequested || trustedWifiWaiting) {
            return
        }
        val now = System.currentTimeMillis()
        if (
            !shouldAttemptSoftRestartNow(
                nowMs = now,
                lastSoftRestartAtMs = lastSoftRestartAtMs,
                minIntervalMs = recoveryPolicy().reconnectMinIntervalMs,
                softRestartCount = softRestartCount,
                force = force,
            )
        ) {
            AppLog.v(TAG, "soft restart deferred (cooldown): $reason")
            return
        }
        lastSoftRestartAtMs = now
        softRestartCount++
        softRestartInProgress = true
        // Handover rebind must still detect dead Direct. Wake grace is for
        // SCREEN_ON / process flaps, not for Wi‑Fi→LTE.
        if (!reason.startsWith("[СЕТЬ]")) {
            wakeRecoveryGraceUntilMs = now + WAKE_RECOVERY_GRACE_MS
        }
        lastHandoffAtMs = now
        zeroWorkersSinceMs = 0L
        processDeadSinceMs = 0L
        if (rebuildTun) rebuildTunOnNextLaunch = true
        val path = pathOverride ?: TunnelSessionHolder.config?.path
        if (path == null) {
            softRestartInProgress = false
            AppLog.e(TAG, "soft restart aborted: no session path")
            return
        }
        if (pathOverride != null && TunnelSessionHolder.config?.path != pathOverride) {
            ConnectionManager.getOrNull()?.applySessionPath(pathOverride)
        }
        AppLog.v(TAG, "soft restart #$softRestartCount path=$path: $reason")
        ConnectionManager.getOrNull()?.onTransportRestarting(reason)
        val restartText = ConnectionManager.getOrNull()?.notificationRunningText()
            ?: "Переподключение транспорта…"
        updateNotification(path, restartText)

        softRestartEpoch++
        val myEpoch = softRestartEpoch
        softRestartJob?.cancel()
        softRestartJob = scope.launch {
            var handedOff = false
            try {
                delay(recoveryPolicy().processRestartDelayMs)
                if (!tunnelSessionActive || userStopRequested || trustedWifiWaiting) {
                    return@launch
                }
                handedOff = true
                launchBackend(path, softRestart = true)
            } finally {
                if (shouldClearSoftRestartFlag(handedOff, myEpoch, softRestartEpoch)) {
                    softRestartInProgress = false
                }
            }
        }
    }

    /** Prefer validated Wi‑Fi, then any validated underlay, then any tracked network. */
    private fun pickBestUnderlyingNetwork(): Network? {
        val cm = connectivityManager ?: return activeNetworks.firstOrNull()
        fun score(n: Network): Int {
            val caps = cm.getNetworkCapabilities(n) ?: return 0
            var s = 1
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) s += 2
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 4
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s += 8
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s += 2
            return s
        }
        return activeNetworks.maxByOrNull { score(it) }
    }

    // ── Screen / Doze wake rescue ───────────────────────────────────────────

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        AppLog.v(TAG, "SCREEN_ON — schedule wake rescue")
                        wakeRecoveryGraceUntilMs = System.currentTimeMillis() + WAKE_RECOVERY_GRACE_MS
                        scheduleWakeRescueCheck()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        wakeRescueJob?.cancel()
                        wakeRescueJob = null
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        wakeRescueJob?.cancel()
        wakeRescueJob = null
    }

    private fun readDefaultDataSubscriptionId(): Int = runCatching {
        android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
    }.getOrDefault(android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    private fun registerDataSubscriptionMonitor() {
        lastDataSubId = readDefaultDataSubscriptionId()
        if (dataSubReceiver == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val subId = readDefaultDataSubscriptionId()
                    onDataSubscriptionChanged(subId, "default data SIM broadcast")
                }
            }
            dataSubReceiver = receiver
            val filter = IntentFilter(ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // System implicit broadcast — must be exported or SIM switches are silent.
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        }
        if (telephonyCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val tm = getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (tm != null) {
                val cb = object :
                    android.telephony.TelephonyCallback(),
                    android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener {
                    override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                        onDataSubscriptionChanged(subId, "active data SIM changed")
                    }
                }
                runCatching { tm.registerTelephonyCallback(mainExecutor, cb) }
                    .onSuccess { telephonyCallback = cb }
                    .onFailure { AppLog.w(TAG, "TelephonyCallback failed: ${it.message}") }
            }
        }
        AppLog.v(TAG, "data subscription monitor lastSubId=$lastDataSubId")
    }

    private fun unregisterDataSubscriptionMonitor() {
        dataSubReceiver?.let { runCatching { unregisterReceiver(it) } }
        dataSubReceiver = null
        val cb = telephonyCallback
        telephonyCallback = null
        if (cb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val tm = getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            runCatching { tm?.unregisterTelephonyCallback(cb) }
        }
    }

    private fun onDataSubscriptionChanged(subId: Int, reason: String) {
        if (subId == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        if (subId == lastDataSubId) return
        val previous = lastDataSubId
        lastDataSubId = subId
        if (!tunnelSessionActive || userStopRequested || trustedWifiWaiting) return
        AppLog.v(TAG, "$reason $previous → $subId")
        scheduleUnderlyingNetworkReconnect("$reason $previous→$subId")
    }

    private fun isDeviceInteractive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    private fun scheduleWakeRescueCheck() {
        if (!tunnelSessionActive || userStopRequested || trustedWifiWaiting) return
        val wakeStartedAt = System.currentTimeMillis()
        wakeRescueJob?.cancel()
        wakeRescueJob = scope.launch {
            delay(WAKE_RESCUE_GRACE_MS)
            if (!tunnelSessionActive || userStopRequested || trustedWifiWaiting) return@launch
            val path = TunnelSessionHolder.config?.path ?: return@launch
            VpnLiveStats.sample()
            val fresh = TransportHealth.hasFreshInboundSince(wakeStartedAt) ||
                TransportHealth.hasFreshStatsSince(wakeStartedAt)
            val directEgressOk = path != VpnPath.Direct ||
                VpnLiveStats.hasFreshRxSince(wakeStartedAt)
            val should = shouldReconnectTunnelAfterWake(
                activeWorkers = TransportHealth.activeWorkers,
                hasFreshStatsSinceWake = fresh,
                bypassPath = path == VpnPath.Bypass,
                backendAlive = sessionJob?.isActive == true || TransportHealth.backendAlive,
                directEgressOk = directEgressOk,
            )
            if (should) {
                AppLog.v(
                    TAG,
                    "wake rescue → soft restart path=$path workers=${TransportHealth.activeWorkers} " +
                        "directRx=${VpnLiveStats.hasFreshRxSince(wakeStartedAt)}",
                )
                updateNotification(path, "Восстановление после сна…")
                requestSoftRestart(
                    reason = "[СОН] После пробуждения нет рабочих каналов. Мягко переподключаем транспорт.",
                    force = true,
                )
            } else {
                AppLog.v(
                    TAG,
                    "wake rescue: path=$path looks healthy " +
                        "bypassFresh=$fresh directRx=${VpnLiveStats.hasFreshRxSince(wakeStartedAt)}",
                )
            }
        }
    }

    // ── Zero-workers / process watchdog ─────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(10_000L)
            while (true) {
                delay(WATCHDOG_POLL_MS)
                if (userStopRequested || trustedWifiWaiting) continue
                if (!tunnelSessionActive) continue
                val path = TunnelSessionHolder.config?.path ?: continue
                val now = System.currentTimeMillis()
                val jobAlive = sessionJob?.isActive == true
                if (
                    path == VpnPath.Direct &&
                    shouldObserveDirectEgress(
                        tunnelRunning = tunnelSessionActive,
                        userStopRequested = userStopRequested,
                        softRestartInProgress = softRestartInProgress,
                    )
                ) {
                    if (!jobAlive) {
                        if (processDeadSinceMs == 0L) processDeadSinceMs = now
                        if (now - processDeadSinceMs >= PROCESS_DEAD_GRACE_MS) {
                            AppLog.w(TAG, "watchdog: backend job dead → recovery")
                            ConnectionManager.getOrNull()?.onWatchdogFault(
                                VpnPath.Direct,
                                "backend-job-dead",
                            )
                            processDeadSinceMs = 0L
                        }
                        continue
                    }
                    processDeadSinceMs = 0L
                    VpnLiveStats.sample()
                    val nowDirect = System.currentTimeMillis()
                    val anchor = maxOf(sessionStartedAtMs, lastHandoffAtMs)
                    val freshRx = VpnLiveStats.hasFreshRxSince(anchor)
                    if (nowDirect - lastDirectHealthLogAtMs >= 15_000L) {
                        lastDirectHealthLogAtMs = nowDirect
                        AppLog.i(
                            TAG,
                            "watchdog: Direct rx=${VpnLiveStats.totalRx} tx=${VpnLiveStats.totalTx} " +
                                "freshRx=$freshRx src=${VpnLiveStats.source} " +
                                "up=${nowDirect - sessionStartedAtMs}ms",
                        )
                    }
                    if (
                        shouldTreatDirectAsDeadNoRx(
                            nowMs = nowDirect,
                            sessionStartedAtMs = sessionStartedAtMs,
                            lastHandoffAtMs = lastHandoffAtMs,
                            hasFreshRxSinceAnchor = freshRx,
                        ) &&
                        nowDirect - deadDirectHandledAtMs > 30_000L
                    ) {
                        deadDirectHandledAtMs = nowDirect
                        AppLog.w(
                            TAG,
                            "watchdog: Direct has no TUN rx for ${nowDirect - anchor}ms — not treating AWG as healthy",
                        )
                        ConnectionManager.getOrNull()?.onDeadDirectNoRx()
                    }
                    continue
                }
                if (
                    !shouldObserveTunnelHealth(
                        deviceInteractive = isDeviceInteractive(),
                        wakeRecoveryGraceActive = System.currentTimeMillis() < wakeRecoveryGraceUntilMs,
                        trustedWifiWaiting = trustedWifiWaiting,
                        softRestartInProgress = softRestartInProgress,
                    )
                ) {
                    continue
                }
                val workers = TransportHealth.activeWorkers
                val permit = ConnectionManager.getOrNull()?.recoveryPermit()
                if (permit?.allowsWatchdogRestart == false) {
                    continue
                }
                if (workers <= 0) {
                    if (zeroWorkersSinceMs == 0L) zeroWorkersSinceMs = now
                    if (
                        shouldSoftRestartForZeroWorkers(
                            activeWorkers = workers,
                            zeroSinceMs = zeroWorkersSinceMs,
                            nowMs = now,
                        )
                    ) {
                        AppLog.w(TAG, "watchdog: zero workers for ${now - zeroWorkersSinceMs}ms")
                        ConnectionManager.getOrNull()?.onWatchdogFault(
                            VpnPath.Bypass,
                            "zero-workers",
                        )
                        zeroWorkersSinceMs = 0L
                    }
                } else {
                    zeroWorkersSinceMs = 0L
                    val bypass = path == VpnPath.Bypass
                    if (
                        shouldSoftRestartForHandshakeStall(
                            bypassPath = bypass,
                            activeWorkers = workers,
                            trafficKb = TransportHealth.trafficKb,
                            nowMs = now,
                            handoffAtMs = lastHandoffAtMs,
                            lastTrafficGrowthAtMs = TransportHealth.lastTrafficGrowthAtMs,
                        )
                    ) {
                        AppLog.w(
                            TAG,
                            "watchdog: Bypass handshake-only traffic=${TransportHealth.trafficKb}KB " +
                                "workers=$workers sinceHandoff=${now - lastHandoffAtMs}ms",
                        )
                        ConnectionManager.getOrNull()?.onWatchdogFault(
                            VpnPath.Bypass,
                            "handshake-stall",
                        )
                    } else if (
                        shouldSoftRestartForTrafficStall(
                            activeWorkers = workers,
                            trafficBytes = TransportHealth.trafficKb,
                            lastTrafficGrowthAtMs = TransportHealth.lastInboundGrowthAtMs,
                            nowMs = now,
                            handoffAtMs = lastHandoffAtMs,
                        )
                    ) {
                        AppLog.w(
                            TAG,
                            "watchdog: traffic stall workers=$workers " +
                                "stalled=${now - TransportHealth.lastTrafficGrowthAtMs}ms " +
                                "sinceHandoff=${if (lastHandoffAtMs > 0) now - lastHandoffAtMs else -1}",
                        )
                        // Same path only. Re-probing Bypass as DirectOk (TCP :9100)
                        // after a dead-Direct fallback yanked a working Bypass.
                        ConnectionManager.getOrNull()?.onWatchdogFault(
                            path,
                            "traffic-stall",
                        )
                    }
                }
            }
        }
    }

    // ── Trusted Wi‑Fi ───────────────────────────────────────────────────────

    private fun scheduleTrustedWifiEvaluation(delayMs: Long) {
        trustedWifiEvalJob?.cancel()
        trustedWifiEvalJob = scope.launch {
            delay(delayMs)
            evaluateTrustedWifi()
        }
    }

    /** React immediately when user toggles/ disables / deletes trusted SSIDs in Settings. */
    private fun startTrustedWifiSettingsObserver() {
        if (trustedWifiSettingsJob?.isActive == true) return
        trustedWifiSettingsJob = scope.launch {
            kotlinx.coroutines.flow.combine(
                settingsRepo.trustedWifiEnabledFlow,
                settingsRepo.trustedWifiSsidsFlow,
            ) { enabled, ssids -> enabled to ssids }
                .distinctUntilChanged()
                .collect {
                    AppLog.v(TAG, "trusted wifi settings changed — re-evaluate")
                    scheduleTrustedWifiEvaluation(0L)
                }
        }
    }

    private suspend fun evaluateTrustedWifi() {
        if (userStopRequested) return
        val (enabled, ssids) = runCatching { settingsRepo.trustedWifiSnapshot() }
            .getOrDefault(false to emptySet())
        val wifi = readConnectedWifiState(this)
        val transition = decideTrustedWifiTransition(
            enabled = enabled,
            tunnelRunning = tunnelSessionActive && !softRestartInProgress,
            waiting = trustedWifiWaiting,
            wifi = wifi,
            trustedSsids = ssids,
        )
        when (transition) {
            TrustedWifiTransition.EnterWaiting -> {
                val ssid = wifi.ssid.ifBlank { "доверенная Wi‑Fi" }
                AppLog.i(TAG, "trusted wifi enter waiting ssid=$ssid")
                // Exclusion wins: cancel probe/handover before pausing VPN.
                networkChangeJob?.cancel()
                networkChangeJob = null
                stableNetworkReconnectPending = false
                handoverPreviousNetworkId = null
                softRestartJob?.cancel()
                softRestartJob = null
                softRestartInProgress = false
                enterTrustedWifiWaiting(ssid)
            }
            TrustedWifiTransition.ResumeVpn -> {
                val sincePause = System.currentTimeMillis() - trustedWifiPausedAtMs
                if (trustedWifiWaiting && wifi.connected && !wifi.ssidAvailable &&
                    sincePause < TRUSTED_WIFI_RESUME_GUARD_MS
                ) {
                    AppLog.v(TAG, "trusted wifi resume suppressed (${sincePause}ms, SSID unreadable)")
                    return
                }
                AppLog.i(TAG, "trusted wifi resume (enabled=$enabled ssids=${ssids.size})")
                resumeFromTrustedWifi("trusted wifi settings/network change")
            }
            TrustedWifiTransition.None -> {
                // Keep polling: SSID often arrives after VALIDATED. Pause as soon as it matches.
                if (
                    enabled &&
                    ssids.isNotEmpty() &&
                    !trustedWifiWaiting &&
                    wifi.connected &&
                    !wifi.ssidAvailable &&
                    tunnelSessionActive
                ) {
                    scheduleTrustedWifiEvaluation(TRUSTED_WIFI_SSID_RETRY_MS)
                }
            }
        }
    }

    private fun enterTrustedWifiWaiting(ssid: String) {
        if (trustedWifiWaiting) return
        trustedWifiWaiting = true
        trustedWifiWaitingSsid = ssid
        trustedWifiPausedAtMs = System.currentTimeMillis()
        tunnelSessionActive = false
        softRestartInProgress = false
        handoverProbeInProgress = false
        pendingHandover = null
        ++backendEpoch
        sessionJob?.cancel()
        sessionJob = null
        discardParkedCall("trusted wifi pause")
        backend?.stop()
        backend = null
        forgetTun()
        TransportHealth.noteBackendStopped()
        ConnectionManager.getOrNull()?.onTrustedWifiWaiting(ssid)
        val path = TunnelSessionHolder.config?.path ?: VpnPath.Direct
        updateNotification(path, "Туннель выключен в «$ssid» · ожидание выхода")
        startForegroundNotification(path, "Туннель выключен в «$ssid» · ожидание выхода")
    }

    private fun resumeFromTrustedWifi(reason: String) {
        if (!trustedWifiWaiting) return
        val savedPath = TunnelSessionHolder.config?.path
        if (savedPath == null) {
            AppLog.e(TAG, "resume from trusted wifi aborted: no session config ($reason)")
            ConnectionManager.getOrNull()?.onTrustedWifiResuming()
            ConnectionManager.getOrNull()?.onTunnelFailed("Нет конфигурации сессии после доверенной Wi‑Fi")
            trustedWifiWaiting = false
            trustedWifiWaitingSsid = ""
            return
        }
        trustedWifiWaiting = false
        trustedWifiWaitingSsid = ""
        tunnelSessionActive = true
        sessionStartedAtMs = System.currentTimeMillis()
        lastHandoffAtMs = sessionStartedAtMs
        lastDirectHealthLogAtMs = 0L
        TransportHealth.reset()
        VpnLiveStats.reset()
        val mode = ConnectionManager.getOrNull()?.currentPathMode() ?: ConnPathMode.Auto
        val kind = currentUnderlayKind()
        val hasHash = ConnectionManager.getOrNull()?.ui?.value?.hasCallHash == true
        val probePath = ConnectionManager.getOrNull()?.ui?.value?.probe?.preselectedPath
        AppLog.i(TAG, "resume from trusted wifi: $reason mode=$mode kind=$kind saved=$savedPath")
        ConnectionManager.getOrNull()?.onTrustedWifiResuming()
        if (trustedWifiResumeNeedsProbe(mode, kind)) {
            updateNotification(savedPath, "Подключение…")
            scheduleUnderlyingNetworkReconnect(
                reason = "Выход из доверенной Wi‑Fi",
                previousNetworkId = lastPreferredUnderlayHandle,
                stickyUnderlayChanged = true,
            )
            return
        }
        val path = resolveTrustedWifiResumePath(
            mode = mode,
            savedPath = savedPath,
            probePath = probePath,
            hasCallHash = hasHash,
            underlayKind = kind,
        )
        if (path != savedPath) {
            ConnectionManager.getOrNull()?.applySessionPath(path)
        }
        updateNotification(path, "Подключение…")
        launchBackend(path, softRestart = true)
    }

    // ── NetworkCallback (handover) ──────────────────────────────────────────

    /**
     * Dedicated Wi‑Fi monitor (WDTT Plus style): evaluates trusted-SSID exclusion
     * on Wi‑Fi transport events, independently of the general internet handover
     * callback below.
     */
    private fun setupTrustedWifiMonitoring() {
        if (trustedWifiNetworkCallback != null) return
        val cm = connectivityManager
            ?: (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                connectivityManager = it
            }
        trustedWifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (trustedWifiWaiting) {
                    // While paused, only debounced exit checks — avoid SSID API flicker loops.
                    scheduleTrustedWifiEvaluation(TRUSTED_WIFI_EXIT_DELAY_MS)
                } else {
                    scheduleTrustedWifiEvaluation(TRUSTED_WIFI_ENTER_DELAY_MS)
                }
            }

            override fun onLost(network: Network) {
                scheduleTrustedWifiEvaluation(
                    if (trustedWifiWaiting) 0L else TRUSTED_WIFI_EXIT_DELAY_MS,
                )
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (trustedWifiWaiting) return
                scheduleTrustedWifiEvaluation(TRUSTED_WIFI_ENTER_DELAY_MS)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, trustedWifiNetworkCallback!!) }
            .onFailure { AppLog.w(TAG, "trusted wifi NetworkCallback failed: ${it.message}") }
    }

    private fun setupNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        activeNetworks.clear()
        lastValidatedNetworkId = null
        lastPreferredUnderlayHandle = pickBestUnderlayNetwork(this)?.networkHandle
        stableNetworkWasLost = false
        pendingHandoverUnderlayChanged = false
        stableNetworkEvidenceSinceMs = 0L

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetworks.add(network)
                // Trusted Wi‑Fi exclusion is evaluated inside handover settle;
                // only schedule a standalone resume check while already paused.
                if (trustedWifiWaiting) {
                    scheduleTrustedWifiEvaluation(TRUSTED_WIFI_EXIT_DELAY_MS)
                }
                if (
                    shouldScheduleAvailableNetworkHandover(
                        previousNetworkWasLost = stableNetworkWasLost,
                        availableRealNetworkCount = activeNetworks.size,
                    )
                ) {
                    bindTunToUnderlay()
                    scheduleUnderlyingNetworkReconnect(
                        "Android обнаружил доступную сеть после потери прежней",
                    )
                }
            }

            override fun onLost(network: Network) {
                val networkLostAt = System.currentTimeMillis()
                activeNetworks.remove(network)
                if (trustedWifiWaiting) {
                    scheduleTrustedWifiEvaluation(0L)
                }
                val lostId = network.networkHandle
                val lostCurrentValidated = lastValidatedNetworkId == lostId
                val lostPreviousHandover = handoverPreviousNetworkId == lostId
                if (lostPreviousHandover) handoverPreviousNetworkId = null
                if (lostCurrentValidated) lastValidatedNetworkId = null

                val shouldTrack = shouldTrackUnderlyingNetworkLoss(
                    tunnelRunning = tunnelSessionActive,
                    userStopRequested = userStopRequested,
                ) && !trustedWifiWaiting
                if (lostCurrentValidated || lostPreviousHandover) {
                    stableNetworkWasLost = shouldTrack
                }
                if (activeNetworks.isEmpty() && shouldTrack) {
                    stableNetworkWasLost = true
                    AppLog.v(TAG, "underlying network lost — waiting")
                    val path = TunnelSessionHolder.config?.path ?: VpnPath.Direct
                    updateNotification(path, "Ожидание сети…")
                    ConnectionManager.getOrNull()?.onUnderlyingNetworkLost()
                } else if (
                    (lostCurrentValidated || lostPreviousHandover) &&
                    shouldScheduleAvailableNetworkHandover(
                        previousNetworkWasLost = stableNetworkWasLost,
                        availableRealNetworkCount = activeNetworks.size,
                    )
                ) {
                    scheduleUnderlyingNetworkReconnect(
                        reason = "Android переключает туннель на другую доступную сеть",
                        evidenceSinceMs = networkLostAt,
                    )
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Wi‑Fi exclusion is owned by setupTrustedWifiMonitoring();
                // here only resume-while-waiting and handover classification.
                if (trustedWifiWaiting) {
                    scheduleTrustedWifiEvaluation(TRUSTED_WIFI_EXIT_DELAY_MS)
                }
                val usable =
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!usable) return
                activeNetworks.add(network)
                val id = network.networkHandle
                val preferred = pickBestUnderlayNetwork(this@VpnTunnelService)?.networkHandle
                if (isSecondaryValidatedNetwork(id, preferred)) {
                    lastPreferredUnderlayHandle = preferred
                    AppLog.v(
                        TAG,
                        "ignore extra VALIDATED network=$id preferred=$preferred",
                    )
                    return
                }
                val previous = lastValidatedNetworkId
                val subId = readDefaultDataSubscriptionId()
                val subChanged =
                    subId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                        lastDataSubId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                        subId != lastDataSubId
                if (subId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    lastDataSubId = subId
                }
                val transition = classifyValidatedNetworkTransition(
                    previousNetworkId = previous,
                    currentNetworkId = id,
                    previousNetworkWasLost = stableNetworkWasLost,
                    dataSubscriptionChanged = subChanged,
                )
                lastValidatedNetworkId = id
                lastPreferredUnderlayHandle = preferred ?: id
                ConnectionManager.getOrNull()?.onUnderlyingNetworkLost()
                if (
                    rebindBypassWhenValidated &&
                    TunnelSessionHolder.config?.path == VpnPath.Bypass &&
                    transition != ValidatedNetworkTransition.HANDOVER
                ) {
                    rebindBypassWhenValidated = false
                    scheduleUnderlyingNetworkReconnect(
                        reason = "Android VALIDATED LTE — перепривязываем обход",
                        previousNetworkId = previous,
                    )
                    return
                }
                rebindBypassWhenValidated = false
                when (transition) {
                    ValidatedNetworkTransition.HANDOVER ->
                        scheduleUnderlyingNetworkReconnect(
                            reason = "Android подтвердил новую рабочую сеть",
                            previousNetworkId = previous,
                        )
                    ValidatedNetworkTransition.INITIAL -> {
                        AppLog.v(TAG, "validated underlying network id=$id")
                        // After Wi‑Fi→LTE, id is often cleared; the new/returning
                        // underlay arrives as INITIAL, not HANDOVER — still re-probe.
                        if (
                            shouldTreatInitialValidatedAsHandover(
                                tunnelRunning = tunnelSessionActive,
                                userStopRequested = userStopRequested,
                                softRestartInProgress = softRestartInProgress,
                                sessionStartedAtMs = sessionStartedAtMs,
                                nowMs = System.currentTimeMillis(),
                            )
                        ) {
                            scheduleUnderlyingNetworkReconnect(
                                reason = "Android подтвердил underlay (INITIAL после смены сети)",
                                previousNetworkId = previous,
                            )
                        }
                    }
                    ValidatedNetworkTransition.UNCHANGED -> Unit
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, networkCallback!!) }
            .onFailure { AppLog.e(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    private fun hasValidatedRealNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        val chosen = pickBestUnderlayNetwork(this) ?: pickBestUnderlyingNetwork() ?: return false
        val caps = cm.getNetworkCapabilities(chosen) ?: return false
        return isValidatedUnderlay(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    private fun scheduleUnderlyingNetworkReconnect(
        reason: String,
        previousNetworkId: Long? = null,
        evidenceSinceMs: Long = System.currentTimeMillis(),
        stickyUnderlayChanged: Boolean = false,
    ) {
        if (trustedWifiWaiting) return
        if (
            !shouldTrackUnderlyingNetworkLoss(
                tunnelRunning = tunnelSessionActive,
                userStopRequested = userStopRequested,
            )
        ) {
            stableNetworkWasLost = false
            pendingHandoverUnderlayChanged = false
            stableNetworkEvidenceSinceMs = 0L
            return
        }
        val underlayChanged = stickyUnderlayChanged ||
            pendingHandoverUnderlayChanged ||
            isConfirmedUnderlayChange(
                previousNetworkWasLost = stableNetworkWasLost,
                previousNetworkId = previousNetworkId,
            )
        if (
            !shouldStartUnderlyingNetworkCheck(
                checkPending = stableNetworkReconnectPending,
                currentJobActive = networkChangeJob?.isActive == true,
            )
        ) {
            if (underlayChanged) pendingHandoverUnderlayChanged = true
            stableNetworkEvidenceSinceMs = updatedUnderlyingNetworkEvidenceSince(
                currentEvidenceSinceMs = stableNetworkEvidenceSinceMs,
                networkEventAtMs = evidenceSinceMs,
            )
            if (handoverPreviousNetworkId == null && previousNetworkId != null) {
                handoverPreviousNetworkId = previousNetworkId
            }
            pendingHandover = mergePendingHandover(
                pendingHandover,
                PendingHandoverEvent(
                    reason = reason,
                    underlayChanged = underlayChanged,
                    previousNetworkId = previousNetworkId ?: handoverPreviousNetworkId,
                    evidenceSinceMs = evidenceSinceMs,
                    generation = networkChangeEpoch,
                    rebuildTun = underlayChanged,
                ),
            )
            AppLog.v(TAG, "$reason; handover check already pending — queued")
            return
        }
        pendingHandoverUnderlayChanged = underlayChanged
        stableNetworkWasLost = false
        stableNetworkReconnectPending = true
        handoverPreviousNetworkId = previousNetworkId
        lastHandoffAtMs = System.currentTimeMillis()
        stableNetworkEvidenceSinceMs = evidenceSinceMs
        val path = TunnelSessionHolder.config?.path ?: VpnPath.Direct
        val policy = transportRecoveryPolicy(path)
        AppLog.v(
            TAG,
            "$reason — wait VALIDATED then settle ${policy.networkSettleDelayMs}ms " +
                "path=$path underlayChanged=$underlayChanged",
        )

        networkChangeEpoch++
        val myEpoch = networkChangeEpoch
        networkChangeJob?.cancel()
        networkChangeJob = scope.launch {
            try {
                val skipValidated = shouldSkipValidatedWait(
                    path = path,
                    underlayKind = currentUnderlayKind(),
                )
                val validatedWaitStart = System.currentTimeMillis()
                val validatedTimeoutMs = validatedWaitTimeoutMs(
                    replacementUnderlayPresent = activeNetworks.isNotEmpty(),
                    skipWait = skipValidated,
                )
                while (
                    shouldKeepWaitingForValidated(
                        validatedPresent = hasValidatedRealNetwork(),
                        waitedMs = System.currentTimeMillis() - validatedWaitStart,
                        timeoutMs = validatedTimeoutMs,
                    )
                ) {
                    delay(VALIDATED_WAIT_POLL_MS)
                }
                val validatedWaited = System.currentTimeMillis() - validatedWaitStart
                if (!hasValidatedRealNetwork()) {
                    rebindBypassWhenValidated = true
                    if (!skipValidated || validatedWaited >= 200L) {
                        AppLog.w(
                            TAG,
                            "handover: underlay not VALIDATED after ${validatedWaited}ms — continue anyway ($reason)",
                        )
                    }
                } else {
                    rebindBypassWhenValidated = false
                    AppLog.v(TAG, "handover: underlay VALIDATED in ${validatedWaited}ms ($reason)")
                }

                val (trustedOn, trustedSsids) = runCatching { settingsRepo.trustedWifiSnapshot() }
                    .getOrDefault(false to emptySet())
                val settlePath = TunnelSessionHolder.config?.path ?: path
                val skipValidatedSettle = shouldSkipValidatedWait(
                    path = settlePath,
                    underlayKind = currentUnderlayKind(),
                )
                val settleMs = extraNetworkSettleDelayMs(
                    path = settlePath,
                    validatedPresent = hasValidatedRealNetwork(),
                    skipValidatedWait = skipValidatedSettle,
                )
                AppLog.v(
                    TAG,
                    "handover settle ${settleMs}ms validated=${hasValidatedRealNetwork()} " +
                        "path=$settlePath ($reason)",
                )
                delay(settleMs)
                if (trustedOn && trustedSsids.isNotEmpty()) {
                    val waitStarted = System.currentTimeMillis()
                    while (!userStopRequested) {
                        evaluateTrustedWifi()
                        if (trustedWifiWaiting) {
                            AppLog.v(TAG, "skip handover after trusted wifi exclusion ($reason)")
                            return@launch
                        }
                        val wifi = readConnectedWifiState(this@VpnTunnelService)
                        val (enabledNow, ssidsNow) = runCatching { settingsRepo.trustedWifiSnapshot() }
                            .getOrDefault(false to emptySet())
                        val waited = System.currentTimeMillis() - waitStarted
                        when (
                            decideTrustedWifiHandoverGate(
                                trustedEnabled = enabledNow,
                                trustedSsids = ssidsNow,
                                wifi = wifi,
                                waitedMs = waited,
                            )
                        ) {
                            TrustedWifiHandoverGate.PauseVpn -> {
                                evaluateTrustedWifi()
                                AppLog.v(TAG, "skip handover after trusted wifi pause ($reason)")
                                return@launch
                            }
                            TrustedWifiHandoverGate.WaitForSsid -> {
                                AppLog.v(TAG, "handover wait for SSID ${waited}ms ($reason)")
                                ConnectionManager.getOrNull()?.onTrustedWifiIdentifying()
                                delay(TRUSTED_WIFI_SSID_RETRY_MS)
                            }
                            TrustedWifiHandoverGate.HoldPath -> {
                                AppLog.w(
                                    TAG,
                                    "SSID unread after ${waited}ms — keep path, skip Direct probe " +
                                        "problem=${wifi.accessProblem} ($reason)",
                                )
                                ConnectionManager.getOrNull()
                                    ?.onTrustedWifiSsidUnreadable(wifi.accessProblem)
                                if (
                                    shouldRunUnderlyingNetworkReconnect(
                                        tunnelRunning = tunnelSessionActive,
                                        userStopRequested = userStopRequested,
                                        softRestartInProgress = softRestartInProgress,
                                        realNetworkAvailable = activeNetworks.isNotEmpty(),
                                    )
                                ) {
                                    requestSoftRestart(
                                        reason = "[СЕТЬ] $reason (SSID неизвестен — тот же путь)",
                                        force = true,
                                    )
                                }
                                return@launch
                            }
                            TrustedWifiHandoverGate.Proceed -> break
                        }
                    }
                } else {
                    evaluateTrustedWifi()
                    if (trustedWifiWaiting) {
                        AppLog.v(TAG, "skip handover after trusted wifi exclusion ($reason)")
                        return@launch
                    }
                }
                if (
                    !shouldRunUnderlyingNetworkReconnect(
                        tunnelRunning = tunnelSessionActive,
                        userStopRequested = userStopRequested,
                        softRestartInProgress = softRestartInProgress,
                        realNetworkAvailable = activeNetworks.isNotEmpty(),
                    )
                ) {
                    if (softRestartInProgress && !userStopRequested && !trustedWifiWaiting) {
                        pendingHandover = mergePendingHandover(
                            pendingHandover,
                            PendingHandoverEvent(
                                reason = reason,
                                underlayChanged = pendingHandoverUnderlayChanged,
                                previousNetworkId = handoverPreviousNetworkId,
                                evidenceSinceMs = stableNetworkEvidenceSinceMs,
                                generation = myEpoch,
                                rebuildTun = pendingHandoverUnderlayChanged,
                            ),
                        )
                    }
                    AppLog.v(TAG, "skip reconnect: session state changed")
                    return@launch
                }
                VpnLiveStats.sample()
                val livePath = TunnelSessionHolder.config?.path ?: path
                val evidence = stableNetworkEvidenceSinceMs
                val validatedNow = hasValidatedRealNetwork()
                val skipRestart = shouldSkipHandoverRestartIfTrafficFresh(
                    bypassTrafficFresh = TransportHealth.hasFreshInboundSince(evidence),
                    directTrafficFresh = VpnLiveStats.hasFreshRxSince(evidence),
                    path = livePath,
                    validatedPresent = validatedNow,
                    underlayKind = currentUnderlayKind(),
                )
                if (skipRestart) {
                    AppLog.v(
                        TAG,
                        "handover: skip restart — $livePath already has inbound traffic " +
                            "since $evidence ($reason)",
                    )
                    return@launch
                }
                if (!validatedNow && livePath == VpnPath.Bypass) {
                    AppLog.v(
                        TAG,
                        "handover: not skipping Bypass — underlay not VALIDATED ($reason)",
                    )
                }
                runHandoverProbeAndRestart(
                    reason,
                    underlayChanged = pendingHandoverUnderlayChanged,
                )
            } finally {
                if (ownsJobEpoch(myEpoch, networkChangeEpoch)) {
                    stableNetworkReconnectPending = false
                    handoverPreviousNetworkId = null
                    scope.launch {
                        delay(50L)
                        if (ownsJobEpoch(myEpoch, networkChangeEpoch)) {
                            drainPendingHandover()
                        }
                    }
                }
            }
        }
    }

    private fun cancelAllRecovery() {
        networkChangeJob?.cancel()
        networkChangeJob = null
        softRestartJob?.cancel()
        softRestartJob = null
        notifLiveJob?.cancel()
        notifLiveJob = null
        trustedWifiSettingsJob?.cancel()
        trustedWifiSettingsJob = null
        stableNetworkReconnectPending = false
        pendingHandoverUnderlayChanged = false
        stableNetworkEvidenceSinceMs = 0L
        rebindBypassWhenValidated = false
        rebuildTunOnNextLaunch = false
        softRestartInProgress = false
        handoverProbeInProgress = false
        pendingHandover = null
        softRestartEpoch++
        networkChangeEpoch++
        trustedWifiEvalJob?.cancel()
        watchdogJob?.cancel()
        watchdogJob = null
        discardParkedCall("recovery cancelled")
        unregisterScreenReceiver()
        unregisterDataSubscriptionMonitor()
        teardownNetworkCallback()
    }

    private fun teardownNetworkCallback() {
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        trustedWifiNetworkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        trustedWifiNetworkCallback = null
        activeNetworks.clear()
        lastValidatedNetworkId = null
        pendingHandoverUnderlayChanged = false
    }

    override fun establishTun(ip: String, dnsCsv: String, mtu: Int): ParcelFileDescriptor? {
        val ipAddr = ip.substringBefore('/')
        val wantMtu = mtu.coerceIn(576, 1500)
        val excludedApps = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.excludedAppsSnapshot() }
        }.getOrDefault(emptySet())
        val whitelist = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.appsWhitelistModeSnapshot() }
        }.getOrDefault(false)
        val excludedHosts = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.excludedHostsSnapshot() }
        }.getOrDefault(emptySet())
        val plan = SplitTunnel.resolve(
            whitelistMode = whitelist,
            selectedApps = excludedApps,
            selfPackage = packageName,
        )
        val filterFingerprint = SplitTunnel.tunFilterFingerprint(
            whitelistMode = whitelist,
            selectedApps = excludedApps,
            excludedHosts = excludedHosts,
            selfPackage = packageName,
        )
        if (whitelist && excludedApps.isEmpty()) {
            AppLog.w(TAG, "empty app whitelist — full tunnel minus transport (qWDTT fail-open)")
        }
        if (
            canReuseBypassTun(
                existingValid = tunStillValid(),
                lastIp = lastTunIp,
                lastDns = lastTunDns,
                lastMtu = lastTunMtu,
                ip = ip,
                dns = dnsCsv,
                mtu = mtu,
                lastFilterFingerprint = lastTunFilterFingerprint,
                filterFingerprint = filterFingerprint,
            )
        ) {
            AppLog.i(TAG, "TUN reused fd=${tun?.fd} ip=$ip mtu=$wantMtu")
            lastTunUnderlayIdentity = underlayIdentity(this)
            lastTunFilterFingerprint = filterFingerprint
            bindTunToUnderlay()
            return tun
        }
        runCatching { tun?.close() }
        tun = null
        val path = TunnelSessionHolder.config?.path
        val bypassTun = path == VpnPath.Bypass
        var appliedFingerprint = filterFingerprint
        var pfd = establishVpnInterface(
            ipAddr = ipAddr,
            wantMtu = wantMtu,
            dnsCsv = dnsCsv,
            bypassTun = bypassTun,
            plan = plan,
            excludedHosts = excludedHosts,
        )
        if (pfd == null && plan.whitelistMode) {
            AppLog.w(TAG, "whitelist TUN failed — fail-open minus transport")
            val fallback = SplitTunnel.resolve(
                whitelistMode = false,
                selectedApps = emptySet(),
                selfPackage = packageName,
            )
            pfd = establishVpnInterface(
                ipAddr = ipAddr,
                wantMtu = wantMtu,
                dnsCsv = dnsCsv,
                bypassTun = bypassTun,
                plan = fallback,
                excludedHosts = excludedHosts,
            )
            if (pfd != null) {
                appliedFingerprint = SplitTunnel.tunFilterFingerprint(
                    whitelistMode = false,
                    selectedApps = emptySet(),
                    excludedHosts = excludedHosts,
                    selfPackage = packageName,
                )
            }
        }
        if (pfd == null) {
            repeat(2) { attempt ->
                AppLog.w(TAG, "TUN establish null — retry ${attempt + 1}/2")
                android.os.SystemClock.sleep(300)
                pfd = establishVpnInterface(
                    ipAddr = ipAddr,
                    wantMtu = wantMtu,
                    dnsCsv = dnsCsv,
                    bypassTun = bypassTun,
                    plan = plan,
                    excludedHosts = excludedHosts,
                )
                if (pfd != null) return@repeat
            }
        }
        tun = pfd
        if (pfd != null) {
            lastTunIp = ipAddr
            lastTunDns = dnsCsv
            lastTunMtu = wantMtu
            lastTunUnderlayIdentity = underlayIdentity(this)
            lastTunFilterFingerprint = appliedFingerprint
            bindTunToUnderlay()
        } else {
            lastTunIp = null
            lastTunDns = null
            lastTunMtu = 0
            lastTunUnderlayIdentity = null
            lastTunFilterFingerprint = null
        }
        AppLog.i(
            TAG,
            "TUN established ip=$ip mtu=$wantMtu fd=${pfd?.fd} path=$path " +
                "apps=${excludedApps.size} whitelist=$whitelist " +
                "planWhitelist=${plan.whitelistMode} hosts=${excludedHosts.size} " +
                "disallowed=${plan.disallowed.size} allowed=${plan.allowed.size} " +
                SplitTunnel.logSample(excludedApps),
        )
        return pfd
    }

    private fun establishVpnInterface(
        ipAddr: String,
        wantMtu: Int,
        dnsCsv: String,
        bypassTun: Boolean,
        plan: SplitTunnelPlan,
        excludedHosts: Set<String>,
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(if (bypassTun) "ARDTT-raw" else "ARDTT")
            .setMtu(wantMtu)
            .addAddress(ipAddr, 32)
            .addRoute("0.0.0.0", 0)
        dnsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { d ->
            runCatching { builder.addDnsServer(d) }
        }
        // qWDTT RawTunVpnService: allowBypass() is never called. On some OEMs it
        // lets browsers skip the tunnel even with 0.0.0.0/0.
        // Direct is IPv4-only (AWG allowed_ip=0.0.0.0/0). Without AF_INET,
        // Chrome Happy Eyeballs waits on dead IPv6 and sites look offline
        // even while AWG rx/tx grow. Bypass stays on qWDTT defaults.
        if (plan.whitelistMode) {
            for (pkg in plan.allowed) {
                if (!isInstalledPackage(pkg)) continue
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "skip allowed app $pkg: ${it.message}") }
            }
        } else {
            for (pkg in plan.disallowed) {
                if (!isInstalledPackage(pkg)) continue
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "skip disallowed app $pkg: ${it.message}") }
            }
        }
        // Domain → IPv4 excludeRoute (API 33+). IPv6 /128 enables Happy Eyeballs black-holes.
        applyExcludedHostRoutes(builder, excludedHosts)
        // Direct (AWG) keeps the previous blocking/metered flags. Bypass matches
        // qWDTT: default non-blocking TUN, no setMetered.
        if (!bypassTun) {
            builder.allowFamily(OsConstants.AF_INET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            builder.setBlocking(true)
        }
        return runCatching { builder.establish() }
            .onFailure { err ->
                AppLog.e(TAG, "TUN establish threw whitelist=${plan.whitelistMode}: ${err.message}")
            }
            .getOrNull()
    }

    private fun isInstalledPackage(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun tunStillValid(): Boolean =
        runCatching { tun?.fileDescriptor?.valid() == true }.getOrDefault(false)

    private fun forgetTun() {
        runCatching { tun?.close() }
        tun = null
        lastTunIp = null
        lastTunDns = null
        lastTunMtu = 0
        lastTunUnderlayIdentity = null
        lastTunFilterFingerprint = null
    }

    /**
     * Tell Android which real network this VpnService sits on. After a SIM
     * swap the default underlay moved; leaving the previous binding makes
     * apps look offline even when TURN workers already rebound.
     */
    /**
     * Bind the process to VALIDATED Wi‑Fi/LTE *before* AWG creates UDP
     * sockets. [bindSocketToUnderlay] after [awgTurnOn] EPERMs on this
     * OnePlus (Android 16) — the fd is already connected.
     */
    fun pinProcessToUnderlay(): String {
        if (parkedBypass != null) return "skipped-parked-bypass"
        val cm = connectivityManager
            ?: getSystemService(ConnectivityManager::class.java)
            ?: return "no-cm"
        if (connectivityManager == null) connectivityManager = cm
        val n = pickBestUnderlayNetwork(this) ?: pickBestUnderlyingNetwork()
            ?: return "no-underlay"
        val caps = cm.getNetworkCapabilities(n)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!validated) return "underlay-not-validated"
        return try {
            cm.bindProcessToNetwork(n)
            "process-bound:${n.networkHandle}"
        } catch (e: Exception) {
            "process-fail:${e.message}"
        }
    }

    fun unpinProcessFromUnderlay() {
        val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.bindProcessToNetwork(null) }
    }

    /**
     * Pin an already-[protect]ed AWG UDP fd to the VALIDATED Wi‑Fi/LTE
     * underlay. On some OEMs protect() alone still lets native sockets
     * leave via another VPN (WARP) — handshake fits, HTTPS dies.
     * Best-effort: this phone EPERMs bindSocket on an already-connected fd.
     */
    fun bindSocketToUnderlay(fd: Int): String {
        if (fd < 0) return "skip"
        val n = pickBestUnderlayNetwork(this) ?: pickBestUnderlyingNetwork()
            ?: return "no-underlay"
        val caps = connectivityManager?.getNetworkCapabilities(n)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!validated) return "underlay-not-validated"
        return try {
            ParcelFileDescriptor.fromFd(fd).use { pfd ->
                n.bindSocket(pfd.fileDescriptor)
            }
            "bound:${n.networkHandle}"
        } catch (e: Exception) {
            "bind-fail:${e.message}"
        }
    }

    private fun bindTunToUnderlay() {
        if (!tunnelSessionActive && tun == null) return
        val n = pickBestUnderlayNetwork(this) ?: pickBestUnderlyingNetwork()
        val caps = n?.let { connectivityManager?.getNetworkCapabilities(it) }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        // Pin only a VALIDATED underlay. Binding to a half-up LTE (common while
        // the VPN is the default network) blackholes apps; qWDTT never pins.
        val bind = n.takeIf { validated }
        runCatching {
            setUnderlyingNetworks(bind?.let { arrayOf(it) })
            AppLog.i(
                TAG,
                "VPN underlying=${bind?.networkHandle ?: "default"}" +
                    if (n != null && !validated) " (not VALIDATED — not pinned)" else "",
            )
        }.onFailure {
            AppLog.w(TAG, "setUnderlyingNetworks: ${it.message}")
        }
    }

    private fun currentUnderlayKind(): UnderlayKind {
        val pick = run {
            val n = pickBestUnderlayNetwork(this) ?: pickBestUnderlyingNetwork()
                ?: return@run UnderlayKind.Other
            val caps = connectivityManager?.getNetworkCapabilities(n) ?: return@run UnderlayKind.Other
            classifyUnderlayKind(
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }
        return preferWifiUnderlayKind(
            hasValidatedWifi = hasValidatedWifiUnderlay(this),
            pickBestKind = pick,
            wifiConnected = readConnectedWifiState(this, requireBackground = false).connected,
        )
    }

    private fun parkBypassCall(backend: BypassBackend) {
        parkedCallExpireJob?.cancel()
        parkedCallExpireJob = null
        if (parkedBypass !== backend) {
            parkedBypass?.setParkedDeathHandler(null)
            parkedBypass?.stop()
        }
        backend.parkCall()
        parkedBypass = backend
        parkedCallEpoch = activeCallEpoch
        backend.setParkedDeathHandler {
            AppLog.w(TAG, "Parked RAW process died — call identity kept, resume disabled")
            if (parkedBypass === backend) {
                parkedBypass = null
            }
            backend.setParkedDeathHandler(null)
            ConnectionManager.getOrNull()?.onParkedBypassDied()
        }
        AppLog.i(TAG, "Parked VK call kept without 5-minute expiry")
    }

    private fun discardParkedCall(reason: String) {
        parkedCallExpireJob?.cancel()
        parkedCallExpireJob = null
        val parked = parkedBypass
        parkedBypass = null
        if (parked != null) {
            AppLog.i(TAG, "Stopping parked VK call ($reason)")
            parked.setParkedDeathHandler(null)
            parked.stop()
        }
        parkedCallEpoch = 0L
    }

    private fun discardActiveBypass(reason: String) {
        val current = backend as? BypassBackend ?: return
        AppLog.i(TAG, "Stopping active Bypass ($reason)")
        current.setParkedDeathHandler(null)
        current.stop()
        if (backend === current) {
            backend = null
        }
        activeCallEpoch = 0L
    }

    private fun applyExcludedHostRoutes(builder: Builder, hosts: Set<String>) {
        if (hosts.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "host exclusions need API 33+; stored ${hosts.size} but not applied")
            return
        }
        val routes = HostExclusion.ipv4RoutesFor(hosts) { resolveHostAddresses(it) }
        for (route in routes) {
            runCatching {
                val prefix = android.net.IpPrefix(route.address, route.prefixLength)
                builder.excludeRoute(prefix)
                Log.i(TAG, "excludeRoute ${route.key}")
            }.onFailure { Log.w(TAG, "excludeRoute ${route.key}: ${it.message}") }
        }
    }

    private fun resolveHostAddresses(name: String): List<java.net.InetAddress> {
        val found = LinkedHashSet<java.net.InetAddress>()
        fun absorb(addrs: Array<out java.net.InetAddress>?) {
            addrs?.forEach { found.add(it) }
        }
        pickBestUnderlyingNetwork()?.let { net ->
            runCatching { absorb(net.getAllByName(name)) }
                .onFailure { Log.w(TAG, "underlay DNS $name: ${it.message}") }
        }
        runCatching { absorb(java.net.InetAddress.getAllByName(name)) }
            .onFailure { Log.w(TAG, "system DNS $name: ${it.message}") }
        return found.toList()
    }

    private fun stopSession(keepService: Boolean) {
        tunnelSessionActive = false
        if (!keepService) {
            cancelAllRecovery()
        }
        sessionJob?.cancel()
        sessionJob = null
        discardParkedCall("session stop")
        backend?.stop()
        backend = null
        forgetTun()
        TransportHealth.noteBackendStopped()
        if (!keepService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        userStopRequested = true
        trustedWifiWaiting = false
        stopSession(keepService = false)
        scope.cancel()
        ConnectionManager.getOrNull()?.onServiceStopped()
        com.ardtt.app.TunnelWidgetProvider.updateWidgetState(
            this,
            running = false,
            statsText = null,
        )
        com.ardtt.app.QuickToggleTileService.requestTileUpdate(this)
        com.ardtt.app.AppShortcuts.refreshAsync(this)
        super.onDestroy()
    }

    private fun startNotifLiveUpdates() {
        notifLiveJob?.cancel()
        notifLiveJob = scope.launch {
            while (true) {
                if (tunnelSessionActive && !userStopRequested) {
                    val showInShade = runCatching {
                        settingsRepo.vpnNotificationVisibleSnapshot()
                    }.getOrDefault(true)
                    if (showInShade) {
                        VpnLiveStats.sample()
                        val path = TunnelSessionHolder.config?.path
                        if (path != null) {
                            updateNotification(path, "live")
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun updateNotification(path: VpnPath, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(path, text))
        pushQuickLaunchState()
    }

    private fun startForegroundNotification(path: VpnPath, text: String) {
        val notification = buildNotification(path, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        pushQuickLaunchState()
    }

    private fun pushQuickLaunchState() {
        com.ardtt.app.TunnelWidgetProvider.pushFromConnection(this)
        com.ardtt.app.QuickToggleTileService.requestTileUpdate(this)
        com.ardtt.app.AppShortcuts.refreshAsync(this)
    }

    private fun buildNotification(path: VpnPath, text: String): Notification {
        val showInShade = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.vpnNotificationVisibleSnapshot() }
        }.getOrDefault(true)
        val channelId = if (showInShade) CHANNEL_SHADE else CHANNEL_MIN
        val importance = if (showInShade) {
            NotificationManager.IMPORTANCE_DEFAULT
        } else {
            NotificationManager.IMPORTANCE_MIN
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            listOf(
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
            ).forEach { legacy ->
                runCatching { nm.deleteNotificationChannel(legacy) }
            }
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    if (showInShade) {
                        getString(R.string.notif_channel_tunnel)
                    } else {
                        getString(R.string.notif_channel_tunnel_min)
                    },
                    importance,
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    description = if (showInShade) {
                        "Уведомление о состоянии подключения и команда остановки"
                    } else {
                        "Служебная запись службы подключения. Система не позволяет скрыть её полностью."
                    }
                    lockscreenVisibility = if (showInShade) {
                        Notification.VISIBILITY_PUBLIC
                    } else {
                        Notification.VISIBILITY_SECRET
                    }
                },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Hidden mode: Android still requires an FGS notification — keep it minimal under «Без звука».
        if (!showInShade) {
            val builder = NotificationCompat.Builder(this, channelId)
                .setContentTitle("ARDTT")
                .setContentText("Туннель")
                .setSmallIcon(R.drawable.ic_stat_connected)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setLocalOnly(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_DEFERRED,
                )
            }
            return builder.build()
        }

        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, VpnTunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val appsWhitelist = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.appsWhitelistModeSnapshot() }
        }.getOrDefault(false)
        val shade = ConnectionManager.getOrNull()?.notificationShadeContent(
            sessionStartedAtMs = sessionStartedAtMs,
            appsWhitelist = appsWhitelist,
        ) ?: ConnectionManager.ShadeContent(
            title = when (path) {
                VpnPath.Direct -> "Прямое подключение"
                VpnPath.Bypass -> "Обход"
            },
            ip = "…",
            pathLabel = when (path) {
                VpnPath.Direct -> "Прямое"
                VpnPath.Bypass -> "Обход"
            },
            showTotals = false,
            showWhitelistIcon = appsWhitelist,
            statusText = text.ifBlank { getString(R.string.notif_running) },
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(shade.title)
            .setContentText(shade.ip)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Match qWDTT: silent shade updates (no sound/vibration on live stats).
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val remote = runCatching { buildShadeRemoteViews(shade) }.getOrElse { t ->
            AppLog.e(TAG, "shade RemoteViews failed: ${t.message}")
            null
        }
        if (remote != null) {
            builder.setCustomContentView(remote)
                .setCustomBigContentView(remote)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        } else {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(shade.rates, shade.totals.takeIf { shade.showTotals }, shade.summary)
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                ),
            )
        }

        if (sessionStartedAtMs > 0L && !trustedWifiWaiting) {
            builder.setWhen(sessionStartedAtMs)
                .setUsesChronometer(true)
                .setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
            )
        }
        if (!trustedWifiWaiting) {
            builder.addAction(0, getString(R.string.notif_stop), stopPi)
        }
        return builder.build()
    }

    private fun buildShadeRemoteViews(shade: ConnectionManager.ShadeContent): RemoteViews {
        return RemoteViews(packageName, R.layout.notif_vpn_shade).apply {
            setTextViewText(R.id.notif_title, shade.title)
            val pathColor = when {
                shade.title.contains("Обход", ignoreCase = true) ||
                    shade.pathLabel.contains("Обход", ignoreCase = true) ->
                    0xFF1565C0.toInt()
                shade.title.contains("Прям", ignoreCase = true) ||
                    shade.pathLabel.contains("Прям", ignoreCase = true) ->
                    0xFF2E7D32.toInt()
                else -> 0
            }
            if (pathColor != 0) {
                setTextColor(R.id.notif_title, pathColor)
            }
            val status = shade.statusText
            if (!status.isNullOrBlank()) {
                setViewVisibility(R.id.notif_status, android.view.View.VISIBLE)
                setViewVisibility(R.id.notif_stats_row, android.view.View.GONE)
                setTextViewText(R.id.notif_status, status)
            } else {
                setViewVisibility(R.id.notif_status, android.view.View.GONE)
                setViewVisibility(R.id.notif_stats_row, android.view.View.VISIBLE)
                setTextViewText(R.id.notif_rates, shade.rates)
            }
            setTextViewText(R.id.notif_ip, shade.ip)
            setViewVisibility(
                R.id.notif_warp_icon,
                if (shade.showWarpIcon) android.view.View.VISIBLE else android.view.View.GONE,
            )
            setViewVisibility(
                R.id.notif_rkn_icon,
                if (shade.showWhitelistIcon) android.view.View.VISIBLE else android.view.View.GONE,
            )
        }
    }

    companion object {
        private const val TAG = "VpnTunnel"
        const val ACTION_START = "com.ardtt.app.action.START"
        const val ACTION_STOP = "com.ardtt.app.action.STOP"
        const val ACTION_RESTART_TRANSPORT = "com.ardtt.app.action.RESTART_TRANSPORT"
        const val ACTION_REFRESH_NOTIFICATION = "com.ardtt.app.action.REFRESH_NOTIFICATION"
        const val ACTION_SESSION_CONTROL = "com.ardtt.app.action.SESSION_CONTROL"
        const val EXTRA_PATH = "path"
        const val EXTRA_HIDE_IP = "hide_ip"
        const val EXTRA_TUN_ADDRESS = "tun_address"
        const val EXTRA_RESTART_REASON = "restart_reason"
        const val EXTRA_REBUILD_TUN = "rebuild_tun"
        const val EXTRA_NET_OPS_ALLOWED = "net_ops_allowed"
        const val EXTRA_NETWORK_HANDLE = "network_handle"
        const val EXTRA_NETWORK_KIND = "network_kind"
        const val EXTRA_NETWORK_SCOPE = "network_scope"
        const val EXTRA_DISCARD_PARKED = "discard_parked"
        const val EXTRA_DISCARD_ACTIVE = "discard_active"
        const val EXTRA_CALL_EPOCH = "call_epoch"
        const val EXTRA_IDENTITY_TOKEN = "identity_token"
        const val NETWORK_SCOPE_ACTIVE = "active"
        const val NETWORK_SCOPE_PARKED = "parked"
        private const val ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED =
            "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"
        private const val NOTIF_ID = 42
        private const val CHANNEL_SHADE = "ardtt_vpn_shade_v6"
        private const val CHANNEL_MIN = "ardtt_vpn_min_v6"
        private const val TRUSTED_WIFI_RESUME_GUARD_MS = 8_000L
        /** AWG-over-WARP underlay needs headroom under 1280. */
        const val DIRECT_TUN_MTU = 1200
    }
}

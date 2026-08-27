package com.nonamevpn.app.core

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
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nonamevpn.app.MainActivity
import com.nonamevpn.app.R
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.tunnel.BypassBackend
import com.nonamevpn.app.tunnel.DirectBackend
import com.nonamevpn.app.tunnel.TunEstablisher
import com.nonamevpn.app.tunnel.TunnelBackend
import com.nonamevpn.app.tunnel.TunnelBackendState
import com.nonamevpn.app.tunnel.TunnelSessionHolder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
    private val activeNetworks = ConcurrentHashMap.newKeySet<Network>()
    @Volatile private var lastValidatedNetworkId: Long? = null
    @Volatile private var stableNetworkWasLost = false
    @Volatile private var handoverPreviousNetworkId: Long? = null
    @Volatile private var stableNetworkReconnectPending = false
    @Volatile private var networkChangeJob: Job? = null
    @Volatile private var softRestartJob: Job? = null
    @Volatile private var userStopRequested = false
    @Volatile private var softRestartInProgress = false
    @Volatile private var backendEpoch: Int = 0
    @Volatile private var tunnelSessionActive = false
    @Volatile private var lastSoftRestartAtMs = 0L
    @Volatile private var softRestartCount = 0
    private val recoveryPolicy = transportRecoveryPolicy()

    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var wakeRescueJob: Job? = null
    @Volatile private var wakeRecoveryGraceUntilMs = 0L
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var zeroWorkersSinceMs = 0L
    @Volatile private var processDeadSinceMs = 0L
    @Volatile private var lastHandoffAtMs = 0L
    @Volatile private var sessionStartedAtMs = 0L
    private var notifLiveJob: Job? = null
    private var trustedWifiSettingsJob: Job? = null

    @Volatile private var trustedWifiWaiting = false
    @Volatile private var trustedWifiWaitingSsid = ""
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
                        requestSoftRestart(
                            reason = intent.getStringExtra(EXTRA_RESTART_REASON)
                                ?: "Запрошено переподключение транспорта",
                            force = true,
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
        ConnectionManager.getOrNull()?.onServiceStarted(path)
        AppLog.i(TAG, "session start path=$path")

        tunnelSessionActive = true
        softRestartInProgress = false
        sessionStartedAtMs = System.currentTimeMillis()
        TransportHealth.reset()
        VpnLiveStats.reset()
        setupNetworkCallback()
        registerScreenReceiver()
        startWatchdog()
        startNotifLiveUpdates()
        startTrustedWifiSettingsObserver()
        launchBackend(path, softRestart = false)
        scheduleTrustedWifiEvaluation(TRUSTED_WIFI_ENTER_DELAY_MS)
    }

    private fun launchBackend(path: VpnPath, softRestart: Boolean) {
        val config = TunnelSessionHolder.config
        if (config == null) {
            AppLog.e(TAG, "No session config")
            softRestartInProgress = false
            ConnectionManager.getOrNull()?.onTunnelFailed("Нет конфигурации сессии")
            stopSelf()
            return
        }

        val epoch = ++backendEpoch
        sessionJob?.cancel()
        backend?.stop()
        backend = null
        runCatching { tun?.close() }
        tun = null
        TransportHealth.noteBackendStarted()

        val chosen: TunnelBackend = when (path) {
            VpnPath.Direct -> DirectBackend()
            VpnPath.Bypass -> BypassBackend()
        }
        backend = chosen

        val fd: ParcelFileDescriptor? = when (path) {
            VpnPath.Direct -> {
                val address = config.tunAddress.substringBefore('/').takeIf { it.isNotBlank() }
                    ?: config.profile?.direct?.address?.substringBefore('/')
                    ?: "10.8.0.2"
                val mtu = config.profile?.direct?.mtu ?: 1280
                val dns = config.profile?.direct?.dns?.firstOrNull() ?: "10.8.0.1"
                establishTun(address, dns, mtu).also { created ->
                    if (created == null) {
                        AppLog.e(TAG, "TUN establish failed")
                        softRestartInProgress = false
                        ConnectionManager.getOrNull()?.onTunnelFailed("Не удалось создать TUN (отклонён VPN?)")
                        if (!softRestart && !trustedWifiWaiting) stopSelf()
                    } else {
                        AppLog.i(TAG, "TUN ok ip=$address mtu=$mtu soft=$softRestart")
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
                            AppLog.i(TAG, "Running path=$path")
                            softRestartInProgress = false
                            tunnelSessionActive = true
                            TransportHealth.backendAlive = true
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
                        }
                        is TunnelBackendState.Failed -> {
                            if (userStopRequested || trustedWifiWaiting) {
                                AppLog.w(TAG, "Ignoring failure after stop/pause: ${state.message}")
                                return@start
                            }
                            AppLog.e(TAG, "Failed: ${state.message}")
                            softRestartInProgress = false
                            ConnectionManager.getOrNull()?.onTunnelFailed(state.message)
                            stopSelf()
                        }
                        is TunnelBackendState.Stopped -> Unit
                        is TunnelBackendState.Starting -> {
                            AppLog.i(TAG, if (softRestart) "Soft restart starting…" else "Starting…")
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    AppLog.i(TAG, "session cancelled (normal stop/restart)")
                    throw t
                }
                if (epoch != backendEpoch || userStopRequested || trustedWifiWaiting) {
                    AppLog.w(TAG, "Ignoring crash from stale/stop backend: ${t.message}")
                    return@launch
                }
                AppLog.e(TAG, "backend crash: ${t.message}")
                softRestartInProgress = false
                ConnectionManager.getOrNull()?.onTunnelFailed(t.message ?: "tunnel crash")
                stopSelf()
            }
        }
    }

    /**
     * Auto: re-classify underlay and soft-restart (possibly switching Direct↔Bypass).
     * Forced Direct/Bypass: soft-restart same path.
     */
    private suspend fun runHandoverProbeAndRestart(reason: String) {
        if (!tunnelSessionActive || userStopRequested || trustedWifiWaiting) return
        if (softRestartInProgress) {
            AppLog.i(TAG, "handover probe skipped — soft restart already in progress ($reason)")
            return
        }
        softRestartInProgress = true
        try {
            val underlay = pickBestUnderlyingNetwork()
            val decision = ConnectionManager.getOrNull()
                ?.decideNetworkHandover(underlay)
                ?: NetworkHandoverDecision.SoftRestartSamePath
            when (decision) {
                is NetworkHandoverDecision.SwitchPath -> {
                    AppLog.i(TAG, "handover path switch → ${decision.path}")
                    requestSoftRestart(
                        reason = "[СЕТЬ] $reason → ${decision.path}",
                        force = true,
                        pathOverride = decision.path,
                    )
                }
                NetworkHandoverDecision.SoftRestartSamePath -> {
                    requestSoftRestart(reason = "[СЕТЬ] $reason", force = true)
                }
            }
        } catch (t: Throwable) {
            softRestartInProgress = false
            AppLog.e(TAG, "handover probe failed: ${t.message}")
        }
    }

    private fun requestSoftRestart(
        reason: String,
        force: Boolean = false,
        pathOverride: VpnPath? = null,
    ) {
        if ((!tunnelSessionActive && !trustedWifiWaiting) || userStopRequested || trustedWifiWaiting) {
            return
        }
        val now = System.currentTimeMillis()
        if (
            !shouldAttemptSoftRestartNow(
                nowMs = now,
                lastSoftRestartAtMs = lastSoftRestartAtMs,
                minIntervalMs = recoveryPolicy.reconnectMinIntervalMs,
                softRestartCount = softRestartCount,
                force = force,
            )
        ) {
            AppLog.i(TAG, "soft restart deferred (cooldown): $reason")
            return
        }
        lastSoftRestartAtMs = now
        softRestartCount++
        softRestartInProgress = true
        wakeRecoveryGraceUntilMs = now + WAKE_RECOVERY_GRACE_MS
        zeroWorkersSinceMs = 0L
        processDeadSinceMs = 0L
        val path = pathOverride ?: TunnelSessionHolder.config?.path
        if (path == null) {
            softRestartInProgress = false
            AppLog.e(TAG, "soft restart aborted: no session path")
            return
        }
        AppLog.i(TAG, "soft restart #$softRestartCount path=$path: $reason")
        ConnectionManager.getOrNull()?.onTransportRestarting(reason)
        val restartText = ConnectionManager.getOrNull()?.notificationRunningText()
            ?: "Переподключение транспорта…"
        updateNotification(path, restartText)

        softRestartJob?.cancel()
        softRestartJob = scope.launch {
            var handedOff = false
            try {
                delay(recoveryPolicy.processRestartDelayMs)
                if (!tunnelSessionActive || userStopRequested || trustedWifiWaiting) {
                    return@launch
                }
                handedOff = true
                launchBackend(path, softRestart = true)
            } finally {
                if (!handedOff) {
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
                        AppLog.i(TAG, "SCREEN_ON — schedule wake rescue")
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
            val fresh = TransportHealth.hasFreshStatsSince(wakeStartedAt)
            val should = shouldReconnectTunnelAfterWake(
                activeWorkers = TransportHealth.activeWorkers,
                hasFreshStatsSinceWake = fresh,
                bypassPath = path == VpnPath.Bypass,
                backendAlive = sessionJob?.isActive == true || TransportHealth.backendAlive,
            )
            if (should) {
                AppLog.i(TAG, "wake rescue → soft restart workers=${TransportHealth.activeWorkers}")
                updateNotification(path, "Восстановление после сна…")
                requestSoftRestart(
                    reason = "[СОН] После пробуждения нет рабочих каналов. Мягко переподключаем транспорт.",
                    force = true,
                )
            } else {
                AppLog.i(TAG, "wake rescue: transport looks healthy")
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
                if (!tunnelSessionActive) continue
                val path = TunnelSessionHolder.config?.path ?: continue
                val now = System.currentTimeMillis()
                val jobAlive = sessionJob?.isActive == true
                if (!jobAlive) {
                    if (processDeadSinceMs == 0L) processDeadSinceMs = now
                    if (now - processDeadSinceMs >= PROCESS_DEAD_GRACE_MS) {
                        AppLog.w(TAG, "watchdog: backend job dead → soft restart")
                        requestSoftRestart(reason = "[ЗДОРОВЬЕ] Процесс туннеля не отвечает", force = false)
                        processDeadSinceMs = 0L
                    }
                    continue
                } else {
                    processDeadSinceMs = 0L
                }
                if (path != VpnPath.Bypass) continue
                val workers = TransportHealth.activeWorkers
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
                        requestSoftRestart(
                            reason = "[ЗДОРОВЬЕ] Нет активных TURN-воркеров — переподключаем обход",
                            force = true,
                        )
                        zeroWorkersSinceMs = 0L
                    }
                } else {
                    zeroWorkersSinceMs = 0L
                    if (
                        shouldSoftRestartForTrafficStall(
                            activeWorkers = workers,
                            trafficBytes = TransportHealth.trafficKb,
                            lastTrafficGrowthAtMs = TransportHealth.lastTrafficGrowthAtMs,
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
                        // Re-probe: e.g. Wi‑Fi returned while stuck on Bypass zombies.
                        scope.launch {
                            runHandoverProbeAndRestart(
                                "Трафик встал после смены сети — проверка пути",
                            )
                        }
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
                    AppLog.i(TAG, "trusted wifi settings changed — re-evaluate")
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
                enterTrustedWifiWaiting(ssid)
            }
            TrustedWifiTransition.ResumeVpn -> {
                AppLog.i(TAG, "trusted wifi resume (enabled=$enabled ssids=${ssids.size})")
                resumeFromTrustedWifi("trusted wifi settings/network change")
            }
            TrustedWifiTransition.None -> Unit
        }
    }

    private fun enterTrustedWifiWaiting(ssid: String) {
        if (trustedWifiWaiting) return
        trustedWifiWaiting = true
        trustedWifiWaitingSsid = ssid
        tunnelSessionActive = false
        softRestartInProgress = false
        ++backendEpoch
        sessionJob?.cancel()
        sessionJob = null
        backend?.stop()
        backend = null
        runCatching { tun?.close() }
        tun = null
        TransportHealth.noteBackendStopped()
        ConnectionManager.getOrNull()?.onTrustedWifiWaiting(ssid)
        val path = TunnelSessionHolder.config?.path ?: VpnPath.Direct
        updateNotification(path, "VPN выключен в «$ssid» · ожидание выхода")
        startForegroundNotification(path, "VPN выключен в «$ssid» · ожидание выхода")
    }

    private fun resumeFromTrustedWifi(reason: String) {
        if (!trustedWifiWaiting) return
        val path = TunnelSessionHolder.config?.path
        if (path == null) {
            AppLog.e(TAG, "resume from trusted wifi aborted: no session config ($reason)")
            // Keep waiting=true so a later restart / settings change can retry;
            // surface UI so the user is not stuck on a silent pause.
            ConnectionManager.getOrNull()?.onTrustedWifiResuming()
            ConnectionManager.getOrNull()?.onTunnelFailed("Нет конфигурации сессии после доверенной Wi‑Fi")
            trustedWifiWaiting = false
            trustedWifiWaitingSsid = ""
            return
        }
        trustedWifiWaiting = false
        trustedWifiWaitingSsid = ""
        AppLog.i(TAG, "resume from trusted wifi: $reason")
        ConnectionManager.getOrNull()?.onTrustedWifiResuming()
        tunnelSessionActive = true
        updateNotification(path, "Подключение…")
        launchBackend(path, softRestart = true)
    }

    // ── NetworkCallback (handover) ──────────────────────────────────────────

    private fun setupNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        activeNetworks.clear()
        lastValidatedNetworkId = null
        stableNetworkWasLost = false

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetworks.add(network)
                if (trustedWifiWaiting) {
                    scheduleTrustedWifiEvaluation(TRUSTED_WIFI_EXIT_DELAY_MS)
                }
                if (
                    shouldScheduleAvailableNetworkHandover(
                        previousNetworkWasLost = stableNetworkWasLost,
                        availableRealNetworkCount = activeNetworks.size,
                    )
                ) {
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
                    AppLog.i(TAG, "underlying network lost — waiting")
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
                        reason = "Android переключает VPN на другую доступную сеть",
                        evidenceSinceMs = networkLostAt,
                    )
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (wifi || trustedWifiWaiting) {
                    scheduleTrustedWifiEvaluation(TRUSTED_WIFI_EXIT_DELAY_MS)
                }
                val usable =
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!usable) return
                activeNetworks.add(network)
                val id = network.networkHandle
                val previous = lastValidatedNetworkId
                val transition = classifyValidatedNetworkTransition(
                    previousNetworkId = previous,
                    currentNetworkId = id,
                    previousNetworkWasLost = stableNetworkWasLost,
                )
                lastValidatedNetworkId = id
                when (transition) {
                    ValidatedNetworkTransition.HANDOVER ->
                        scheduleUnderlyingNetworkReconnect(
                            reason = "Android подтвердил новую рабочую сеть",
                            previousNetworkId = previous,
                        )
                    ValidatedNetworkTransition.INITIAL -> {
                        AppLog.i(TAG, "validated underlying network id=$id")
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

    private fun scheduleUnderlyingNetworkReconnect(
        reason: String,
        previousNetworkId: Long? = null,
        evidenceSinceMs: Long = System.currentTimeMillis(),
    ) {
        if (trustedWifiWaiting) return
        if (
            !shouldTrackUnderlyingNetworkLoss(
                tunnelRunning = tunnelSessionActive,
                userStopRequested = userStopRequested,
            )
        ) {
            stableNetworkWasLost = false
            return
        }
        if (
            !shouldStartUnderlyingNetworkCheck(
                checkPending = stableNetworkReconnectPending,
                currentJobActive = networkChangeJob?.isActive == true,
            )
        ) {
            if (handoverPreviousNetworkId == null && previousNetworkId != null) {
                handoverPreviousNetworkId = previousNetworkId
            }
            AppLog.i(TAG, "$reason; handover check already pending")
            return
        }
        stableNetworkWasLost = false
        stableNetworkReconnectPending = true
        handoverPreviousNetworkId = previousNetworkId
        lastHandoffAtMs = System.currentTimeMillis()
        AppLog.i(TAG, "$reason — settle ${recoveryPolicy.networkSettleDelayMs}ms")

        networkChangeJob?.cancel()
        networkChangeJob = scope.launch {
            try {
                delay(recoveryPolicy.networkSettleDelayMs)
                if (
                    !shouldRunUnderlyingNetworkReconnect(
                        tunnelRunning = tunnelSessionActive,
                        userStopRequested = userStopRequested,
                        softRestartInProgress = softRestartInProgress,
                        realNetworkAvailable = activeNetworks.isNotEmpty(),
                    )
                ) {
                    AppLog.i(TAG, "skip reconnect: session state changed")
                    return@launch
                }
                runHandoverProbeAndRestart(reason)
            } finally {
                stableNetworkReconnectPending = false
                handoverPreviousNetworkId = null
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
        softRestartInProgress = false
        trustedWifiEvalJob?.cancel()
        watchdogJob?.cancel()
        watchdogJob = null
        unregisterScreenReceiver()
        teardownNetworkCallback()
    }

    private fun teardownNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        networkCallback = null
        activeNetworks.clear()
        lastValidatedNetworkId = null
    }

    override fun establishTun(ip: String, dnsCsv: String, mtu: Int): ParcelFileDescriptor? {
        runCatching { tun?.close() }
        tun = null
        val builder = Builder()
            .setSession("ARDTT")
            .setMtu(mtu.coerceIn(576, 1500))
            .addAddress(ip.substringBefore('/'), 32)
            .addRoute("0.0.0.0", 0)
        dnsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { d ->
            runCatching { builder.addDnsServer(d) }
        }
        // App split-tunnel: ЧС = disallowed, БС = allowed (self always included).
        val excludedApps = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.excludedAppsSnapshot() }
        }.getOrDefault(emptySet())
        val whitelist = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.appsWhitelistModeSnapshot() }
        }.getOrDefault(false)
        if (whitelist) {
            runCatching { builder.addAllowedApplication(packageName) }
            for (pkg in excludedApps) {
                if (pkg == packageName) continue
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "skip allowed app $pkg: ${it.message}") }
            }
        } else {
            runCatching { builder.addDisallowedApplication(packageName) }
            for (pkg in excludedApps) {
                if (pkg == packageName) continue
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "skip disallowed app $pkg: ${it.message}") }
            }
        }
        // Domain → IP excludeRoute (API 33+). Best-effort; fails soft on older OS.
        val excludedHosts = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.excludedHostsSnapshot() }
        }.getOrDefault(emptySet())
        applyExcludedHostRoutes(builder, excludedHosts)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.setBlocking(true)
        val pfd = builder.establish()
        tun = pfd
        Log.i(
            TAG,
            "TUN established ip=$ip mtu=$mtu fd=${pfd?.fd} " +
                "apps=${excludedApps.size} whitelist=$whitelist hosts=${excludedHosts.size}",
            )
        return pfd
    }

    private fun applyExcludedHostRoutes(builder: Builder, hosts: Set<String>) {
        if (hosts.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "host exclusions need API 33+; stored ${hosts.size} but not applied")
            return
        }
        for (host in hosts) {
            val ips = resolveHostIps(host)
            for (ip in ips) {
                runCatching {
                    val prefix = android.net.IpPrefix(java.net.InetAddress.getByName(ip), 32)
                    builder.excludeRoute(prefix)
                    Log.i(TAG, "excludeRoute $host → $ip/32")
                }.onFailure { Log.w(TAG, "excludeRoute $host/$ip: ${it.message}") }
            }
        }
    }

    private fun resolveHostIps(host: String): List<String> {
        val clean = host.trim().lowercase().removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
        if (clean.isBlank()) return emptyList()
        // Literal IPv4
        if (clean.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return listOf(clean)
        return runCatching {
            java.net.InetAddress.getAllByName(clean)
                .mapNotNull { it.hostAddress }
                .filter { !it.contains(':') } // IPv4 only for now
                .distinct()
                .take(8)
        }.getOrDefault(emptyList())
    }

    private fun stopSession(keepService: Boolean) {
        tunnelSessionActive = false
        if (!keepService) {
            cancelAllRecovery()
        }
        sessionJob?.cancel()
        sessionJob = null
        backend?.stop()
        backend = null
        runCatching { tun?.close() }
        tun = null
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
            listOf("nvpn_tunnel", "nvpn_tunnel_min", "ardtt_vpn_shade_v1", "ardtt_vpn_min_v1").forEach { legacy ->
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
                        "Плашка VPN: живой статус и «Остановить»"
                    } else {
                        "Техническая запись службы (Android не даёт убрать полностью)"
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
                .setContentText("VPN")
                .setSmallIcon(R.drawable.ic_vpn_key)
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
        val shade = ConnectionManager.getOrNull()?.notificationShadeContent(sessionStartedAtMs)
            ?: ConnectionManager.ShadeContent(
                rates = text.ifBlank { getString(R.string.notif_running) },
                totals = "",
                ip = "…",
                path = when (path) {
                    VpnPath.Direct -> "Прямое подключение"
                    VpnPath.Bypass -> "Обход"
                },
            )
        val remote = buildShadeRemoteViews(shade)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(shade.summary)
            .setCustomContentView(remote)
            .setCustomBigContentView(remote)
            // System header = app name + chronometer (session time), no duplicate brand in body.
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
            setTextViewText(R.id.notif_rates, shade.rates)
            setTextViewText(R.id.notif_totals, shade.totals)
            setTextViewText(R.id.notif_ip, shade.ip)
            setTextViewText(R.id.notif_path, shade.path)
            setViewVisibility(
                R.id.notif_totals,
                if (shade.totals.isBlank()) android.view.View.GONE else android.view.View.VISIBLE,
            )
        }
    }

    companion object {
        private const val TAG = "VpnTunnel"
        const val ACTION_START = "com.nonamevpn.app.action.START"
        const val ACTION_STOP = "com.nonamevpn.app.action.STOP"
        const val ACTION_RESTART_TRANSPORT = "com.nonamevpn.app.action.RESTART_TRANSPORT"
        const val ACTION_REFRESH_NOTIFICATION = "com.nonamevpn.app.action.REFRESH_NOTIFICATION"
        const val EXTRA_PATH = "path"
        const val EXTRA_HIDE_IP = "hide_ip"
        const val EXTRA_TUN_ADDRESS = "tun_address"
        const val EXTRA_RESTART_REASON = "restart_reason"
        private const val NOTIF_ID = 42
        private const val CHANNEL_SHADE = "ardtt_vpn_shade_v3"
        private const val CHANNEL_MIN = "ardtt_vpn_min_v3"
    }
}

package com.ardtt.app.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import com.ardtt.app.bypass.CallHashErrorKind
import com.ardtt.app.bypass.CallHashFailure
import com.ardtt.app.bypass.CallHashOutcome
import com.ardtt.app.bypass.CallHashPhase
import com.ardtt.app.bypass.CallHashStore
import com.ardtt.app.bypass.CallRecreateIdentity
import com.ardtt.app.bypass.CallRecreatePrompt
import com.ardtt.app.bypass.DeadCallAction
import com.ardtt.app.bypass.DialPath
import com.ardtt.app.bypass.VkCallHashGenerator
import com.ardtt.app.bypass.VkLoginActivity
import com.ardtt.app.bypass.VkSession
import com.ardtt.app.bypass.callRecreateIdentityStillCurrent
import com.ardtt.app.bypass.classifyCallHashThrowable
import com.ardtt.app.bypass.evaluateCallRecreateResult
import com.ardtt.app.bypass.planCallRecreateApply
import com.ardtt.app.bypass.decideDeadCallAction
import com.ardtt.app.bypass.isDeadCallMessage
import com.ardtt.app.bypass.userActionForBypassFailure
import com.ardtt.app.telemetry.TelemetryBridge
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.profile.NetworkEndpoint
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.tunnel.TunnelSessionConfig
import com.ardtt.app.tunnel.TunnelSessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class ConnState {
    Idle,
    Probing,
    Ready,
    Connecting,
    Connected,
    /** VPN paused on trusted Wi‑Fi; service may still be foreground waiting to resume. */
    PausedTrustedWifi,
    /** User wants VPN; no usable physical underlay. */
    WaitingForNetwork,
    /** Temporary failure; next attempt is scheduled. */
    Recovering,
    CaptivePortal,
    NeedsUserAction,
    Disconnecting,
    Error,
}

data class ConnUiState(
    val state: ConnState = ConnState.Idle,
    val probe: ProbeResult? = null,
    val activePath: VpnPath? = null,
    val pathMode: ConnPathMode = ConnPathMode.Auto,
    val hideIp: Boolean = false,
    val statusText: String = "Ожидание…",
    val softInfo: String? = null,
    val connectEnabled: Boolean = false,
    val lastError: String? = null,
    val hasCallHash: Boolean = false,
    val callRecreatePrompt: CallRecreatePrompt? = null,
    val uiModel: ConnectionUiModel = ConnectionUiModel(),
)

class ConnectionManager(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(ConnUiState())
    val ui: StateFlow<ConnUiState> = _ui.asStateFlow()
    private val hashStore = CallHashStore(appContext)
    private val settingsRepo = AppSettingsRepository(appContext)
    private var probeJob: Job? = null
    private val connectRequests = ConnectRequestCoordinator()
    private val tunnelStartSerializer = TunnelStartSerializer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var diagnosticJob: Job? = null
    private var connectJob: Job? = null
    private var connectWaitJob: Job? = null
    private var runningNotifyJob: Job? = null
    private val sessionGeneration = AtomicLong(0L)
    private var presenceJob: Job? = null
    private var lastPresenceKey: String = ""
    private var lastPresenceAtMs: Long = 0L
    private var profile: VpnProfile? = null
    private var directEndpoint: String? = null
    private var provisionUrl: String? = null
    private var tunAddress: String? = null
    private var workers: Int = BypassWorkers.DEFAULT
    @Volatile private var silentRecreate: Boolean = false
    @Volatile private var callRecreateAttempts: Int = 0
    private var callRecreateNetworkFails: Int = 0
    private var callRecreateJob: Job? = null
    @Volatile private var vpnPermissionRevoked: Boolean = false
    private var dialPath: DialPath = DialPath.Auto
    private var pathMode: ConnPathMode = ConnPathMode.Auto
    /** Soft transport restart in progress (Wi‑Fi↔LTE); do not treat as user disconnect. */
    @Volatile private var softRestartInProgress: Boolean = false
    @Volatile private var tunnelStartedAtMs: Long = 0L
    /** Consecutive identical Auto probe paths (Direct→Bypass still uses two hits). */
    private var handoverProbeStreak = ProbeStreak()
    /** Last underlay handle we probed / bound for handover. */
    private var lastHandoverBindHandle: Long? = null
    /**
     * Direct died (no TUN rx) on this underlay handle. Do not Auto-upgrade
     * Bypass→Direct on the same network: TCP :9100 / a SYN to 1.1.1.1 can
     * still look open while AWG UDP is dead.
     */
    private var deadDirectBindHandle: Long? = null
    private var blockBypassToDirectUntilUnderlayChange: Boolean = false
    private val diagnosticLog = SessionDiagnosticLog()
    private var recoverySnapshot = ConnectionSnapshot()
    private var waitingNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var requestedCellularNetwork: Network? = null
    private var cellularProbeJob: Job? = null
    private var lastCellularProbeAtMs: Long = 0L
    private var recoveryRetryJob: Job? = null
    private val recoveryWakeLock by lazy { RecoveryWakeLock(appContext, RETRY_WAKELOCK_TAG) }
    private val recoveryTimer = RecoveryTimer(
        arm = { delayMs, fire ->
            recoveryRetryJob?.cancel()
            val hold = recoveryWakeLock.acquire(delayMs)
            recoveryRetryJob = scope.launch {
                try {
                    delay(delayMs.coerceAtLeast(0L))
                    fire()
                } finally {
                    recoveryWakeLock.release(hold)
                }
            }
        },
        cancel = {
            recoveryRetryJob?.cancel()
            recoveryRetryJob = null
            recoveryWakeLock.releaseNow()
        },
    )
    @Volatile private var pendingConnectPath: VpnPath? = null
    private val recoveryGate = Any()

    fun updateProfile(profile: VpnProfile?) {
        this.profile = profile
        if (profile != null) {
            directEndpoint = profile.direct.endpoint
            provisionUrl = profile.provisionBaseUrl
            tunAddress = when (profile.prefer) {
                "bypass" -> profile.bypass.address
                else -> profile.direct.address
            }
            workers = DEFAULT_WORKERS
            refreshHashFlag()
            reportPresenceAsync()
            val revision = PathConfirm.directConfigRevision(profile)
            val existing = TunnelSessionHolder.config
            if (existing != null && existing.profile != null) {
                val oldRev = PathConfirm.directConfigRevision(existing.profile)
                if (oldRev != revision) {
                    // Apply the new Direct/Bypass parameters to the live session holder.
                    TunnelSessionHolder.config = existing.copy(profile = profile)
                }
            }
            if (recoverySnapshot.intent.wantsConnected) {
                dispatchRecovery(
                    ConnectionEvent.SessionParamsChanged(
                        profileId = profile.name,
                        hasCallHash = hashStore.hasHash(profile.name),
                        directConfigRevision = revision,
                    ),
                )
            }
            scheduleCellularPreProbe("profile")
        } else {
            directEndpoint = null
            provisionUrl = null
            tunAddress = null
            refreshHashFlag()
        }
    }

    fun updateEndpoints(
        directEndpoint: String?,
        provisionUrl: String?,
        tunAddress: String? = null,
    ) {
        this.directEndpoint = directEndpoint
        this.provisionUrl = provisionUrl
        this.tunAddress = tunAddress
    }

    private var hideIpSyncJob: Job? = null
    private var transportRestartJob: Job? = null
    private var lastHideIpSent: Boolean? = null

    /** Hide-IP must reach provision after tunnel is up (Bypass / whitelist underlay). */
    @Volatile private var pendingHideIpSync: Boolean = false

    private fun hideIpViaVpn(): Boolean =
        decideHideIpDispatch(_ui.value.state, shouldProvisionViaVpn()) == HideIpDispatch.ViaVpn

    fun bypassWarming(): Boolean {
        val path = _ui.value.activePath ?: TunnelSessionHolder.config?.path
        return isBypassWarming(_ui.value.state, path)
    }

    /** On whitelist / Bypass path, provision :9100 is only reachable through the tunnel. */
    @Suppress("UNUSED_PARAMETER")
    private fun shouldProvisionViaVpn(probe: ProbeResult? = _ui.value.probe): Boolean {
        val path = _ui.value.activePath ?: TunnelSessionHolder.config?.path
        if (path == VpnPath.Bypass) return true
        return false
    }

    private suspend fun syncHideIpToProvision(
        enabled: Boolean,
        viaVpn: Boolean = hideIpViaVpn(),
        tryVpnFallback: Boolean = true,
    ): Result<Unit> = HideIpApi.setHideIp(
        provisionBaseUrl = resolveProvisionUrl(),
        deviceId = profile?.deviceId,
        enabled = enabled,
        context = appContext,
        viaVpn = viaVpn,
        tryVpnFallback = tryVpnFallback,
    )

    fun setHideIp(enabled: Boolean) {
        // TunnelScreen re-enters composition on every tab switch. Do not
        // invalidate egress IP / notification when nothing changed.
        if (_ui.value.hideIp == enabled && lastHideIpSent == enabled) {
            AppLog.v(TAG, "Hide-IP unchanged hideIp=$enabled — skip")
            return
        }
        val cur = _ui.value
        val status = if (cur.state == ConnState.Connected) {
            when (cur.activePath) {
                VpnPath.Direct -> "Подключено: прямое" + if (enabled) hideSuffix(true) else ""
                VpnPath.Bypass -> "Подключено: обход" + if (enabled) hideSuffix(true) else ""
                null -> cur.statusText
            }
        } else {
            cur.statusText
        }
        _ui.value = cur.copy(
            hideIp = enabled,
            statusText = status,
            softInfo = softInfoFor(cur.probe),
            lastError = null,
        )
        // Egress changes after Hide-IP — clear until post-reconnect ifconfig.
        EgressIpProbe.invalidate()
        // Shade notification must reflect Hide-IP immediately (not only after soft-restart).
        refreshVpnNotification()
        pendingHideIpSync = false
        // Debounce rapid toggles — only the final value hits provision/WARP.
        hideIpSyncJob?.cancel()
        hideIpSyncJob = scope.launch {
            delay(900)
            if (_ui.value.hideIp != enabled) return@launch
            if (lastHideIpSent == enabled) {
                AppLog.v(TAG, "Hide-IP already synced hideIp=$enabled — skip")
                scheduleEgressIpRefresh("hide-ip-already-synced")
                return@launch
            }
            val dispatch = decideHideIpDispatch(_ui.value.state, shouldProvisionViaVpn())
            if (dispatch == HideIpDispatch.QueueUntilTunnel) {
                pendingHideIpSync = true
                AppLog.v(TAG, "Hide-IP queued until tunnel (provision unreachable on underlay)")
                return@launch
            }
            val viaVpn = dispatch == HideIpDispatch.ViaVpn
            val r = syncHideIpToProvision(enabled, viaVpn = viaVpn)
            if (r.isSuccess) {
                lastHideIpSent = enabled
                refreshVpnNotification()
                // Proven production WARP (wireproxy+tun2socks) flips egress with
                // ip rule + conntrack flush. Restarting the phone TUN dropped
                // calls and made the toggle feel slow.
                if (_ui.value.state == ConnState.Connected) {
                    delay(1_200) // ardtt-warp debounce (~1s) + apply ip rules
                    if (_ui.value.hideIp != enabled) return@launch
                    if (hideIpShouldRestartTransport()) {
                        val why = if (enabled) "Hide-IP: egress → WARP" else "Hide-IP: egress → VPS"
                        requestTransportRestart(why)
                    }
                    EgressIpProbe.invalidate()
                    scheduleEgressIpRefresh("hide-ip-applied")
                } else {
                    scheduleEgressIpRefresh("hide-ip-synced-offline")
                }
            } else {
                AppLog.e(TAG, "hide-ip sync failed: ${r.exceptionOrNull()?.message}")
                // Retry after TUN is up for both on and off. A failed disable
                // used to set pending=false, so the VPS kept hideIp=true while
                // the app showed «Мой IP» and 2ip.ru still saw Cloudflare.
                pendingHideIpSync = true
                if (viaVpn) {
                    _ui.value = _ui.value.copy(
                        lastError = "Скрытие адреса не синхронизировано: ${r.exceptionOrNull()?.message}",
                    )
                } else {
                    AppLog.i(TAG, "Hide-IP will retry after tunnel up (want=$enabled)")
                }
            }
        }
    }

    private fun resolveProvisionUrl(): String? {
        val fromProfile = profile?.provisionBaseUrl
        if (!fromProfile.isNullOrBlank()) {
            provisionUrl = fromProfile
            return fromProfile
        }
        return provisionUrl?.takeIf { it.isNotBlank() }
    }

    /** Cascade exit provision (:9100), if this profile's server has a second hop. */
    private fun resolveExitProvisionUrl(): String? {
        val host = profile?.let {
            NetworkEndpoint.hostOf(it.direct.endpoint) ?: NetworkEndpoint.hostOf(it.bypass.peer)
        } ?: return null
        val servers = runCatching { ServersRepository.get(appContext).snapshot() }.getOrDefault(emptyList())
        return DeployHop.exitProvisionUrl(DeployHop.matchingServer(servers, host))
    }

    /** Soft-restart transport if a session is up (exclusions / network / manual). */
    fun requestTransportRestart(
        reason: String,
        pathOverride: VpnPath? = null,
        rebuildTun: Boolean = false,
    ) {
        val state = _ui.value.state
        if (state == ConnState.Disconnecting) return
        if (!state.holdsUserSession() && TunnelSessionHolder.config == null) return
        transportRestartJob?.cancel()
        transportRestartJob = scope.launch {
            delay(TRANSPORT_RESTART_DEBOUNCE_MS)
            val live = _ui.value.state
            if (live == ConnState.Disconnecting) return@launch
            if (!live.holdsUserSession() && TunnelSessionHolder.config == null) {
                return@launch
            }
            val intent = Intent(appContext, VpnTunnelService::class.java)
                .setAction(VpnTunnelService.ACTION_RESTART_TRANSPORT)
                .putExtra(VpnTunnelService.EXTRA_RESTART_REASON, reason)
                .putExtra(VpnTunnelService.EXTRA_REBUILD_TUN, rebuildTun)
            if (pathOverride != null) {
                intent.putExtra(VpnTunnelService.EXTRA_PATH, pathOverride.name)
            }
            runCatching { appContext.startService(intent) }
                .onFailure { AppLog.e(TAG, "restart transport failed: ${it.message}") }
        }
    }

    fun refreshVpnNotification() {
        val state = _ui.value.state
        if (
            state != ConnState.Connected &&
            state != ConnState.PausedTrustedWifi &&
            state != ConnState.Connecting
        ) {
            return
        }
        val intent = Intent(appContext, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_REFRESH_NOTIFICATION)
        runCatching { appContext.startService(intent) }
    }

    fun setWorkers(workers: Int) {
        // Path B is TURN-TCP: always the Lab/provision default, not the
        // argument or an older profile JSON that still says 9.
        this.workers = BypassWorkers.DEFAULT
    }

    fun setSilentRecreate(enabled: Boolean) {
        silentRecreate = enabled
    }

    fun setDialPath(path: DialPath) {
        dialPath = path
    }

    fun currentPathMode(): ConnPathMode = pathMode

    private fun bumpSessionGeneration(reason: String): Long {
        val gen = sessionGeneration.incrementAndGet()
        runningNotifyJob?.cancel()
        runningNotifyJob = null
        AppLog.v(TAG, "sessionGeneration=$gen ($reason)")
        return gen
    }

    /**
     * @param switchLive when true (user tapped Авто / Прямое / Обход), switch
     * the running tunnel. DataStore sync from composition must pass false so
     * the initial «auto» value does not tear down Bypass.
     */
    fun setPathMode(mode: ConnPathMode, switchLive: Boolean = false) {
        pathMode = mode
        _ui.value = _ui.value.copy(
            pathMode = mode,
            softInfo = softInfoFor(_ui.value.probe),
        )
        val liveSwitch = switchLive &&
            (
                _ui.value.state == ConnState.Connected ||
                    _ui.value.state == ConnState.Connecting ||
                    _ui.value.state == ConnState.PausedTrustedWifi
                )
        if (recoverySnapshot.intent.wantsConnected) {
            dispatchRecovery(ConnectionEvent.PathModeChanged(mode))
        } else if (liveSwitch) {
            maybeSwitchLivePath(mode)
        }
        scheduleCellularPreProbe("path-mode")
    }

    /**
     * Mid-session Direct ↔ Bypass (or Auto re-probe target) without a full
     * disconnect. No-op when already on that path, paused on trusted Wi‑Fi
     * (config is updated for the next resume), or Bypass lacks a call hash.
     */
    private fun maybeSwitchLivePath(mode: ConnPathMode) {
        val state = _ui.value.state
        if (
            state != ConnState.Connected &&
            state != ConnState.Connecting &&
            state != ConnState.PausedTrustedWifi
        ) {
            return
        }
        val holderPath = TunnelSessionHolder.config?.path
        val current = holderPath ?: _ui.value.activePath
        if (current == null || holderPath == null) {
            AppLog.v(TAG, "Live path switch deferred until tunnel start mode=$mode")
            return
        }
        val hasHash = !callHashOrNull().isNullOrBlank()
        val target = resolveLiveSwitchPath(
            mode = mode,
            currentPath = current,
            probePath = _ui.value.probe?.preselectedPath,
            hasCallHash = hasHash,
            underlayKind = currentAutoUnderlayKind(),
        )
        if (target == null) {
            AppLog.w(TAG, "Live path switch skipped — Bypass needs call hash")
            return
        }
        applySessionPath(target)
        if (!livePathRestartRequired(current, target)) {
            AppLog.v(TAG, "Live path switch: already on $target (mode=$mode)")
            return
        }
        AppLog.i(TAG, "Live path switch: $current → $target (mode=$mode)")
        if (state == ConnState.PausedTrustedWifi) {
            _ui.value = _ui.value.copy(
                activePath = target,
                softInfo = "При возобновлении будет использован путь ${pathLabel(target)}.",
            )
            return
        }
        bumpSessionGeneration("live-path $current→$target")
        softRestartInProgress = true
        _ui.value = _ui.value.copy(
            state = ConnState.Connecting,
            activePath = target,
            statusText = "Выполняется переход на путь ${pathLabel(target)}…",
            lastError = null,
            connectEnabled = true,
        )
        refreshVpnNotification()
        requestTransportRestart(
            reason = "Переключение маршрута: ${pathLabel(current)} → ${pathLabel(target)}",
            pathOverride = target,
        )
    }

    fun saveCallHash(hash: String) {
        val cleaned = com.ardtt.app.bypass.VkUrl.strip(hash)
        if (!com.ardtt.app.bypass.VkUrl.isPlausibleHash(cleaned)) return
        val token = PathConfirm.identityToken(cleaned)
        hashStore.setHash(profile?.name, cleaned)
        refreshHashFlag()
        dispatchRecovery(
            ConnectionEvent.CallIdentityChanged(
                profileId = profile?.name,
                hashPresent = true,
                identityToken = token,
            ),
        )
    }

    fun clearCallHash() {
        hashStore.clear()
        refreshHashFlag()
        dispatchRecovery(
            ConnectionEvent.CallIdentityChanged(
                profileId = profile?.name,
                hashPresent = false,
                identityToken = "",
            ),
        )
    }

    fun callHashOrNull(): String? = hashStore.getHash(profile?.name)

    fun recoveryPermit(): RecoveryPermit = recoverySnapshot.recovery.permit

    fun sessionDiagnostics(): Map<SessionDiagnostic, Int> = diagnosticLog.counts()

    fun retryNow() {
        recoveryTimer.clear()
        dispatchRecovery(ConnectionEvent.UserRetryNow)
    }

    fun openNetworkSettings() {
        runCatching {
            appContext.startActivity(internetConnectivitySettingsIntent())
        }.onFailure {
            AppLog.w(TAG, "network settings: ${it.message}")
        }
    }

    fun openCaptivePortal() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val wifi = cm?.let { pickWifiUnderlayNetwork(it) } ?: pickBestUnderlayNetwork(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null && wifi != null) {
            val started = runCatching {
                ConnectivityManager::class.java
                    .getMethod("startCaptivePortalApp", Network::class.java)
                    .invoke(cm, wifi)
                true
            }.getOrDefault(false)
            if (started) return
        }
        runCatching {
            appContext.startActivity(captivePortalLoginIntent())
        }
    }

    private fun dispatchRecovery(event: ConnectionEvent, elapsedMs: Long = SystemClock.elapsedRealtime()): ReduceResult {
        synchronized(recoveryGate) {
            val previousToken = recoverySnapshot.call.identityToken
            val previousCallEpoch = recoverySnapshot.call.callEpoch
            val jitter = Random.nextInt(0, 151)
            val result = ConnectionReducer.reduce(recoverySnapshot, event, elapsedMs, jitterPermille = jitter)
            recoverySnapshot = result.state
            if (result.state.call.identityToken != previousToken &&
                result.command !is RecoveryCommand.DiscardStaleCall
            ) {
                requestDiscardCallSession(
                    staleCallEpoch = previousCallEpoch,
                    stopActive = true,
                    reason = "call-identity-changed",
                )
            }
            result.diagnostic?.let { kind ->
                diagnosticLog.record(
                    SessionDiagnosticEvent(
                        kind = kind,
                        sessionEpoch = result.state.sessionEpoch,
                        networkEpoch = result.state.networkEpoch,
                        transportEpoch = result.state.transportEpoch,
                        fromPath = _ui.value.activePath,
                        toPath = result.state.activePath,
                        attempt = result.state.recovery.failureIndex,
                        durationMs = 0L,
                        reason = kind.name,
                    ),
                )
            }
            applyRecoveryUi(result.state)
            executeRecoveryCommand(result)
            return result
        }
    }

    fun isRecoveryInFlight(): Boolean = recoverySnapshot.recovery.inFlight

    private fun executeRecoveryCommand(result: ReduceResult) {
        when (val cmd = result.command) {
            RecoveryCommand.None -> Unit
            RecoveryCommand.StopAll -> {
                recoveryTimer.clear()
                stopWatchingUnderlay()
                scheduleCellularPreProbe("stop")
            }
            RecoveryCommand.PauseNetOps -> {
                watchUnderlayUntilUsable()
                if (result.state.transport == TransportLifecycle.Paused ||
                    result.state.transport == TransportLifecycle.Running ||
                    result.state.parkedRawAlive
                ) {
                    requestGoNetOps(allowed = false)
                }
            }
            RecoveryCommand.Probe -> startInitialProbe()
            is RecoveryCommand.StartDirect,
            RecoveryCommand.ParkBypassForDirect,
            -> {
                connectRequests.activeRequestId()?.let { connectRequests.markLaunched(it) }
                startRecoveryTransport(
                    path = VpnPath.Direct,
                    resumeExisting = false,
                    rebuildTun = false,
                )
            }
            is RecoveryCommand.StartBypass -> {
                connectRequests.activeRequestId()?.let { connectRequests.markLaunched(it) }
                startRecoveryTransport(
                    path = VpnPath.Bypass,
                    resumeExisting = false,
                    rebuildTun = false,
                )
            }
            RecoveryCommand.ResumeParkedRaw -> startRecoveryTransport(
                path = VpnPath.Bypass,
                resumeExisting = true,
                rebuildTun = false,
            )
            RecoveryCommand.RebuildRawSameCall -> startRecoveryTransport(
                path = VpnPath.Bypass,
                resumeExisting = false,
                rebuildTun = true,
            )
            RecoveryCommand.RefreshCredentials -> {
                recoveryTimer.clear()
                requestGoNetOps(allowed = true)
                if (tunnelServiceLikelyRunning()) {
                    bumpSessionGeneration("refresh-credentials")
                    applySessionPath(VpnPath.Bypass)
                    requestTransportRestart(
                        reason = "refresh credentials",
                        pathOverride = VpnPath.Bypass,
                        rebuildTun = false,
                    )
                } else {
                    pendingConnectPath = VpnPath.Bypass
                    launchConnectJob()
                }
            }
            is RecoveryCommand.ScheduleRetry -> {
                recoveryTimer.schedule(cmd.delayMs) {
                    dispatchRecovery(ConnectionEvent.Clock(SystemClock.elapsedRealtime()))
                }
            }
            is RecoveryCommand.ScheduleReeval -> {
                AppLog.i(TAG, "auto-stage reeval_scheduled delayMs=${cmd.delayMs}")
                recoveryTimer.schedule(cmd.delayMs) {
                    AppLog.i(TAG, "auto-stage reeval_fired")
                    dispatchRecovery(ConnectionEvent.Clock(SystemClock.elapsedRealtime()))
                }
            }
            is RecoveryCommand.DiscardStaleCall -> {
                recoveryTimer.clear()
                requestDiscardCallSession(
                    staleCallEpoch = cmd.staleCallEpoch,
                    stopActive = cmd.stopActive,
                    reason = "call-identity-changed",
                )
                if (cmd.then !is RecoveryCommand.None && cmd.then !is RecoveryCommand.DiscardStaleCall) {
                    executeRecoveryCommand(result.copy(command = cmd.then))
                }
            }
        }
    }

    private fun tunnelServiceLikelyRunning(): Boolean =
        _ui.value.state.holdsUserSession() && TunnelSessionHolder.config != null

    /**
     * Resume/rebuild reuse the live VPN service. Cold start still goes through
     * [launchConnectJob] so Direct can establish a new TUN.
     */
    private fun startRecoveryTransport(
        path: VpnPath,
        resumeExisting: Boolean,
        rebuildTun: Boolean,
    ) {
        recoveryTimer.clear()
        stopWatchingUnderlay()
        requestGoNetOps(allowed = true)
        pendingConnectPath = path
        bumpSessionGeneration("recovery-transport path=$path resume=$resumeExisting rebuild=$rebuildTun")
        if (resumeExisting && tunnelServiceLikelyRunning()) {
            applySessionPath(path)
            requestTransportRestart(
                reason = if (path == VpnPath.Direct) {
                    "start Direct"
                } else {
                    "resume parked RAW"
                },
                pathOverride = path,
                rebuildTun = rebuildTun,
            )
            return
        }
        if (!resumeExisting && rebuildTun && tunnelServiceLikelyRunning()) {
            applySessionPath(path)
            requestTransportRestart(
                reason = "rebuild RAW same call",
                pathOverride = path,
                rebuildTun = true,
            )
            return
        }
        if (path == VpnPath.Direct && tunnelServiceLikelyRunning()) {
            applySessionPath(path)
            requestTransportRestart(
                reason = "start Direct",
                pathOverride = path,
                rebuildTun = rebuildTun,
            )
            return
        }
        launchConnectJob()
    }

    private fun requestGoUpdateNetwork(underlay: UnderlaySnapshot) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val cellularHandle = cm?.let {
            pickCellularUnderlayNetwork(it, activeCellularSubscriptionId(appContext))?.networkHandle
        }
        val target = goBypassNetworkTarget(
            activePath = recoverySnapshot.activePath,
            transport = recoverySnapshot.transport,
            parkedRawAlive = recoverySnapshot.parkedRawAlive,
            underlayHandle = underlay.handle,
            underlayKind = underlay.kind,
            cellularHandle = cellularHandle,
        ) ?: return
        val intent = Intent(appContext, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_SESSION_CONTROL)
            .putExtra(VpnTunnelService.EXTRA_NETWORK_HANDLE, target.handle)
            .putExtra(VpnTunnelService.EXTRA_NETWORK_KIND, target.kind)
            .putExtra(VpnTunnelService.EXTRA_NETWORK_SCOPE, target.scope)
        runCatching { appContext.startService(intent) }
    }

    private fun requestGoNetOps(allowed: Boolean) {
        val live = recoverySnapshot.transport == TransportLifecycle.Running ||
            recoverySnapshot.transport == TransportLifecycle.Paused ||
            recoverySnapshot.parkedRawAlive
        if (!live) return
        val intent = Intent(appContext, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_SESSION_CONTROL)
            .putExtra(VpnTunnelService.EXTRA_NET_OPS_ALLOWED, allowed)
        runCatching { appContext.startService(intent) }
    }

    private fun applyRecoveryUi(snapshot: ConnectionSnapshot) {
        val model = snapshot.ui
        _ui.value = _ui.value.copy(
            state = model.connState,
            statusText = model.message,
            softInfo = model.details ?: _ui.value.softInfo,
            connectEnabled = true,
            lastError = if (model.connState == ConnState.Error ||
                model.connState == ConnState.NeedsUserAction
            ) {
                model.message
            } else {
                null
            },
            uiModel = model,
            activePath = snapshot.activePath ?: _ui.value.activePath,
        )
    }

    internal fun readUnderlaySnapshot(): UnderlaySnapshot {
        val network = pickBestUnderlayNetwork(appContext)
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val caps = network?.let { cm?.getNetworkCapabilities(it) }
        val lp = network?.let { cm?.getLinkProperties(it) }
        val inventory = cm?.let { scanPhysicalNetworkPresence(it) } ?: PhysicalNetworkPresence()
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val notVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val notSuspendedCap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) != false
        } else {
            true
        }
        val selectedWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val selectedCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val selectedEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val wifiLive = readConnectedWifiState(appContext, requireBackground = false).connected
        val presence = mergePhysicalPresence(
            selectedWifi = selectedWifi,
            selectedCellular = selectedCellular,
            selectedEthernet = selectedEthernet,
            inventoryWifi = inventory.wifi,
            inventoryCellular = inventory.cellular,
            inventoryEthernet = inventory.ethernet,
        )
        val kind = effectiveUnderlayKind(
            selectedKind = classifyUnderlayKind(selectedWifi, selectedCellular, selectedEthernet),
            wifiConnected = wifiLive,
            cellularConnected = presence.cellular,
            ethernetConnected = presence.ethernet,
        )
        val dataSuspended = runCatching {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.dataState == TelephonyManager.DATA_SUSPENDED
        }.getOrDefault(false)
        val notSuspended = selectedNotSuspended(
            selectedKind = kind,
            selectedNotSuspendedCap = notSuspendedCap,
            cellularDataSuspended = dataSuspended,
        )
        val captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        val complete = caps != null
        val availability = classifyUnderlayAvailability(
            hasInternet = hasInternet,
            notVpn = notVpn,
            capabilitiesComplete = complete,
            notSuspended = notSuspended,
            captivePortal = captive,
        )
        val handle = network?.networkHandle
        val simId = activeCellularSubscriptionId(appContext).takeIf {
            it != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val fingerprint = fingerprintFromLinkProperties(lp).ifBlank {
            "$handle|$kind|${simId ?: ""}"
        }
        val key = if (handle != null && availability != UnderlayAvailability.None) {
            NetworkKey(
                handle = handle,
                transport = kind,
                simId = if (kind == UnderlayKind.Cellular) simId else null,
                configFingerprint = fingerprint,
                carrier = if (kind == UnderlayKind.Cellular) {
                    cellularCarrierId(appContext, activeCellularSubscriptionId(appContext))
                } else {
                    null
                },
            )
        } else {
            null
        }
        // A PLMN change keeps the sockets but invalidates every measurement, so
        // probes started before the roam must not be accepted afterwards.
        val scopeChanged = recoverySnapshot.underlay.key.restrictionScopeChanged(key)
        val epoch = recoverySnapshot.networkEpoch + if (scopeChanged) 1L else 0L
        return UnderlaySnapshot(
            key = key,
            kind = kind,
            availability = availability,
            handle = handle,
            validated = validated,
            notSuspended = notSuspended,
            captivePortal = captive,
            simId = simId,
            wifiConnected = wifiLive,
            cellularConnected = presence.cellular,
            ethernetConnected = presence.ethernet,
            capabilitiesComplete = complete,
            networkEpoch = if (scopeChanged) epoch else recoverySnapshot.networkEpoch,
        )
    }

    private fun watchUnderlayUntilUsable() {
        if (waitingNetworkCallback != null) return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = maybeResumeAfterUnderlay("available")
            override fun onLost(network: Network) = maybeResumeAfterUnderlay("lost")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                maybeResumeAfterUnderlay("caps")
        }
        waitingNetworkCallback = cb
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                cb,
            )
        }
    }

    private fun stopWatchingUnderlay() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        waitingNetworkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        waitingNetworkCallback = null
    }

    private fun maybeResumeAfterUnderlay(reason: String) {
        scope.launch {
            val snap = readUnderlaySnapshot()
            requestGoUpdateNetwork(snap)
            val result = dispatchRecovery(ConnectionEvent.UnderlayUpdated(snap))
            if (
                result.command is RecoveryCommand.StartDirect ||
                result.command is RecoveryCommand.StartBypass ||
                result.command is RecoveryCommand.ParkBypassForDirect ||
                result.command is RecoveryCommand.ResumeParkedRaw
            ) {
                stopWatchingUnderlay()
            }
            scheduleCellularPreProbe("underlay-watch")
        }
        AppLog.v(TAG, "underlay watch $reason")
    }

    private fun refreshHashFlag() {
        _ui.value = _ui.value.copy(
            hasCallHash = hashStore.hasHash(profile?.name),
        )
        com.ardtt.app.QuickToggleTileService.requestTileUpdate(appContext)
    }

    fun startInitialProbe() {
        if (_ui.value.state.holdsUserSession()) {
            AppLog.v(TAG, "Probe skipped — tunnel busy (${_ui.value.state})")
            scheduleCellularPreProbe("session-busy")
            return
        }
        if (_ui.value.callRecreatePrompt != null) {
            AppLog.v(TAG, "Probe skipped — waiting for call recreate")
            return
        }
        if (probeJob?.isActive == true) {
            connectRequests.retainInFlightProbe()
            AppLog.v(TAG, "Probe kept — round already in flight")
            return
        }
        probeJob?.cancel()
        val seriesId = java.util.UUID.randomUUID().toString()
        val probeSessionEpoch = recoverySnapshot.sessionEpoch
        val probeNetworkEpoch = recoverySnapshot.networkEpoch
        val capturedKey = readUnderlaySnapshot().key
        val capturedProfileId = recoverySnapshot.intent.profileId ?: profile?.name
        val role = if (connectRequests.activeRequestId() != null) {
            ProbeRole.ConnectInitial
        } else {
            ProbeRole.IdleDiagnostic
        }
        connectRequests.registerProbe(
            role = role,
            seriesId = seriesId,
            sessionEpoch = probeSessionEpoch,
            networkEpoch = probeNetworkEpoch,
            profileId = capturedProfileId,
            networkKey = capturedKey,
            requestId = connectRequests.activeRequestId(),
        )
        probeJob = scope.launch {
            AppLog.v(TAG, "Probe start endpoint=$directEndpoint provision=$provisionUrl")
            val kind = currentAutoUnderlayKind()
            val underlay = readUnderlaySnapshot()
            _ui.value = _ui.value.copy(
                state = ConnState.Probing,
                statusText = "Определение сети…",
                softInfo = null,
                connectEnabled = shouldStartDirectWithoutDiagnostic(
                    pathMode,
                    kind,
                    underlay.availability == UnderlayAvailability.Usable,
                ),
                lastError = null,
            )
            val bind = pickBestUnderlayNetwork(appContext)
            val result = if (autoUsesDirectOnWifi(pathMode, kind)) {
                AppLog.v(TAG, "Probe skipped — Auto on Wi-Fi always Direct kind=$kind")
                wifiAutoDirectProbe()
            } else {
                NetworkProbe.probe(
                    appContext,
                    provisionUrl,
                    directEndpoint = directEndpoint,
                    bindNetwork = bind,
                    quick = true,
                    seriesId = seriesId,
                    onFastDecision = { fast ->
                        applyFastPathHint(
                            fast,
                            sessionEpoch = probeSessionEpoch,
                            networkEpoch = probeNetworkEpoch,
                            capturedNetworkKey = capturedKey,
                            capturedProfileId = capturedProfileId,
                        )
                    },
                )
            }
            AppLog.v(
                TAG,
                "Probe done path=${result.preselectedPath} class=${result.networkClass} " +
                    "yandex=${result.yandexOk} cloudflare=${result.bigtechOk} " +
                    "vps=${result.provisionOk} ${result.elapsedMs}ms kind=$kind",
            )
            applyProbe(
                result,
                sessionEpoch = probeSessionEpoch,
                networkEpoch = probeNetworkEpoch,
                capturedNetworkKey = capturedKey,
                capturedProfileId = capturedProfileId,
            )
        }
        scheduleCellularPreProbe("initial")
    }

    /**
     * Rounds of the whitelist (БС) probe. Only transports that
     * [WhitelistDetection] still scores pay for it — a Wi‑Fi round measured
     * four targets and then had its score stubbed to zero anyway.
     */
    private fun launchBackgroundDiagnostic(
        sessionEpoch: Long,
        networkEpoch: Long,
    ) {
        if (!WhitelistDetection.appliesTo(currentAutoUnderlayKind())) {
            diagnosticJob?.cancel()
            diagnosticJob = null
            AppLog.v(TAG, "diagnostic skipped — whitelist detection is cellular-only")
            return
        }
        diagnosticJob?.cancel()
        val seriesId = java.util.UUID.randomUUID().toString()
        val capturedKey = recoverySnapshot.underlay.key
        val capturedProfileId = recoverySnapshot.intent.profileId
        connectRequests.registerProbe(
            role = ProbeRole.BackgroundDiagnostic,
            seriesId = seriesId,
            sessionEpoch = sessionEpoch,
            networkEpoch = networkEpoch,
            profileId = capturedProfileId,
            networkKey = capturedKey,
            requestId = connectRequests.activeRequestId(),
        )
        val bind = pickBestUnderlayNetwork(appContext)
        diagnosticJob = scope.launch {
            AppLog.i(TAG, "auto-stage probe_round_started series=$seriesId")
            val result = NetworkProbe.probe(
                appContext,
                provisionUrl,
                directEndpoint = directEndpoint,
                bindNetwork = bind,
                quick = false,
                seriesId = seriesId,
            )
            if (recoverySnapshot.sessionEpoch != sessionEpoch) return@launch
            if (capturedKey != null &&
                recoverySnapshot.underlay.key != null &&
                !capturedKey.samePhysicalNetwork(recoverySnapshot.underlay.key)
            ) {
                AppLog.v(TAG, "diagnostic discarded — network changed")
                return@launch
            }
            if (capturedProfileId != null &&
                recoverySnapshot.intent.profileId != null &&
                capturedProfileId != recoverySnapshot.intent.profileId
            ) {
                AppLog.v(TAG, "diagnostic discarded — profile changed")
                return@launch
            }
            AppLog.i(
                TAG,
                "auto-stage probe_round_completed series=$seriesId " +
                    "yandex=${result.yandexOutcome} cf=${result.bigtechOutcome} " +
                    "google=${result.googleOutcome} ru=${result.ruServiceOutcome} " +
                    "provision=${result.provisionOutcome}",
            )
            applyProbe(
                result,
                sessionEpoch = sessionEpoch,
                networkEpoch = networkEpoch,
                capturedNetworkKey = capturedKey,
                capturedProfileId = capturedProfileId,
            )
            val evidence = recoverySnapshot.evidence
            if (recoverySnapshot.underlay.kind != UnderlayKind.Cellular) return@launch
            val now = SystemClock.elapsedRealtime()
            val delayMs = RecoverySettings.nextDiagnosticDelayMs(
                evidence = evidence,
                nowElapsedMs = now,
                key = recoverySnapshot.underlay.key,
                profileId = recoverySnapshot.intent.profileId,
            ) ?: return@launch
            AppLog.i(
                TAG,
                "auto-stage probe_next_in_ms=$delayMs completed=${evidence?.completedSeries ?: 0} " +
                    "restriction=${evidence?.restrictionAt(now, recoverySnapshot.underlay.key, recoverySnapshot.intent.profileId)} " +
                    "sample=${evidence?.lastSample} seriesCount=${evidence?.seriesCount ?: 0}",
            )
            delay(delayMs)
            if (recoverySnapshot.networkEpoch != networkEpoch) return@launch
            if (recoverySnapshot.sessionEpoch != sessionEpoch) return@launch
            launchBackgroundDiagnostic(sessionEpoch, recoverySnapshot.networkEpoch)
        }
    }

    /**
     * Connect from a widget / shortcut after the process was cold.
     * Loads nothing itself — caller must [updateProfile] first — but waits
     * for the Auto probe that [connect] would otherwise ignore.
     */
    fun connectWhenReady() = enqueueUserConnect(ConnectEntryPoint.Widget)

    fun connect() = enqueueUserConnect(ConnectEntryPoint.Button)

    private fun currentConnectLaunchContext(entry: ConnectEntryPoint): ConnectLaunchContext {
        val underlay = readUnderlaySnapshot()
        val liveKey = underlay.key
        val kind = currentAutoUnderlayKind()
        return ConnectLaunchContext(
            entry = entry,
            uiState = _ui.value.state,
            mode = pathMode,
            underlayKind = kind,
            underlayUsable = underlay.availability == UnderlayAvailability.Usable,
            underlayKey = liveKey,
            profileId = recoverySnapshot.intent.profileId ?: profile?.name,
            sessionEpoch = recoverySnapshot.sessionEpoch,
            networkEpoch = recoverySnapshot.networkEpoch,
            hasSameNetworkProbeEvidence = liveWhitelistEvidence(liveKey).let { ev ->
                hasSameNetworkProbeEvidence(
                    ev,
                    liveKey,
                    recoverySnapshot.intent.profileId,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
            },
            probeJobActive = probeJob?.isActive == true,
            transportStartingOrLive = recoverySnapshot.transport == TransportLifecycle.Starting ||
                recoverySnapshot.transport == TransportLifecycle.Running ||
                _ui.value.state == ConnState.Connecting ||
                _ui.value.state == ConnState.Connected,
            hasCallHash = callHashOrNull() != null,
        )
    }

    private fun enqueueUserConnect(entry: ConnectEntryPoint) {
        if (profile == null) {
            AppLog.w(TAG, "Connect ignored — no profile")
            return
        }
        when (val action = connectRequests.onConnectRequested(currentConnectLaunchContext(entry))) {
            ConnectLaunchAction.Ignore -> {
                AppLog.v(TAG, "Connect ignored by coordinator state=${_ui.value.state}")
            }
            is ConnectLaunchAction.EnqueueWait -> {
                AppLog.i(
                    TAG,
                    "Connect waits for cellular whitelist probe request=${action.requestId} " +
                        "startProbe=${action.startProbe} already=${action.alreadyWaiting}",
                )
                if (action.startProbe) {
                    startInitialProbe()
                }
                if (!action.alreadyWaiting) {
                    connectWaitJob?.cancel()
                    connectWaitJob = scope.launch {
                        try {
                            connectRequests.awaitDecision(action.requestId)
                        } catch (_: CancellationException) {
                            return@launch
                        }
                        val cont = connectRequests.continueAfterWait(
                            currentConnectLaunchContext(entry),
                            action.requestId,
                        )
                        if (cont is ConnectLaunchAction.Proceed) {
                            proceedUserConnect(inheritProbeSessionEpoch = cont.inheritProbeSessionEpoch)
                        } else {
                            AppLog.v(
                                TAG,
                                "connect wait skipped after probe state=${_ui.value.state}",
                            )
                        }
                    }
                }
            }
            is ConnectLaunchAction.Proceed ->
                proceedUserConnect(inheritProbeSessionEpoch = action.inheritProbeSessionEpoch)
        }
    }

    private fun proceedUserConnect(inheritProbeSessionEpoch: Long? = null) {
        vpnPermissionRevoked = false
        val current = _ui.value
        val mode = pathMode
        if (current.state == ConnState.Connecting && recoverySnapshot.recovery.inFlight &&
            recoverySnapshot.transport == TransportLifecycle.Starting
        ) {
            AppLog.w(TAG, "Connect ignored (state=${current.state} mode=$mode)")
            return
        }
        if (current.state == ConnState.Connected) return
        if (current.state == ConnState.PausedTrustedWifi) {
            AppLog.i(TAG, "Connect ignored — paused on trusted Wi‑Fi (leave network or disable feature)")
            return
        }
        val underlay = readUnderlaySnapshot()
        val adopted = whitelistEvidenceForUnderlay(
            recoverySnapshot.evidence,
            recoverySnapshot.cellularEvidence,
            underlay.key,
            recoverySnapshot.intent.profileId ?: profile?.name,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        synchronized(recoveryGate) {
            recoverySnapshot = recoverySnapshot.copy(
                underlay = underlay,
                networkEpoch = underlay.networkEpoch,
                evidence = if (underlay.kind == UnderlayKind.Cellular && adopted != null) {
                    adopted
                } else {
                    recoverySnapshot.evidence
                },
            )
        }
        val connectEvent = if (needsFreshUserConnect(current.state, recoverySnapshot.intent.wantsConnected)) {
            ConnectionEvent.UserConnect(
                mode = mode,
                profileId = profile?.name,
                hasCallHash = callHashOrNull() != null,
                silentRecreate = silentRecreate,
                callIdentityToken = PathConfirm.identityToken(callHashOrNull()),
                directConfigRevision = PathConfirm.directConfigRevision(profile),
                inheritProbeSessionEpoch = inheritProbeSessionEpoch
                    ?: connectRequests.inheritEpochIfProbeInFlight(probeJob?.isActive == true),
            )
        } else {
            ConnectionEvent.UnderlayUpdated(underlay)
        }
        val reduced = dispatchRecovery(connectEvent)
        AppLog.v(TAG, "Connect dispatch phase=${reduced.state.recovery.phase} cmd=${reduced.command}")
    }

    private fun launchConnectJob() {
        if (profile == null) {
            AppLog.w(TAG, "Connect ignored — no profile")
            pendingConnectPath = null
            return
        }
        handoverProbeStreak = ProbeStreak()
        lastHandoverBindHandle = null
        deadDirectBindHandle = null
        blockBypassToDirectUntilUnderlayChange = false
        callRecreateAttempts = 0
        bumpSessionGeneration("connect")
        connectWaitJob?.cancel()
        connectWaitJob = null

        // Sync Hide-IP preference to VPS (policy route via warp0). WARP must be up.
        if (_ui.value.hideIp) {
            AppLog.v(TAG, "Hide-IP on — asking provision to route host via WARP")
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            val connectSessionEpoch = recoverySnapshot.sessionEpoch
            val connectNetworkEpoch = recoverySnapshot.networkEpoch
            val connectCapturedKey = recoverySnapshot.underlay.key
            try {
                val snap = _ui.value
                val lastGood = snap.probe
                val liveMode = pathMode
                val kind = currentAutoUnderlayKind()
                val bypassAllowed = callHashOrNull() != null
                val forced = pendingConnectPath
                pendingConnectPath = null
                val wifiAutoDirect = autoUsesDirectOnWifi(liveMode, kind)
                val underlayUsable = readUnderlaySnapshot().availability == UnderlayAvailability.Usable
                val now = SystemClock.elapsedRealtime()
                val evidence = liveWhitelistEvidence()
                val whitelistScore = evidence?.historicalWhitelistScore(
                    recoverySnapshot.underlay.key,
                    recoverySnapshot.intent.profileId,
                ) ?: 0
                val whitelistBypass = autoMayUseBypass(liveMode, kind, bypassAllowed) &&
                    RestrictionScore.mayEnterBypassForWhitelist(
                        whitelistScore,
                        evidence?.hasFreshStrong(
                            now,
                            recoverySnapshot.underlay.key,
                            recoverySnapshot.intent.profileId,
                        ) == true,
                    )
                val skipProbe = forced == VpnPath.Bypass ||
                    shouldSkipConnectProbe(liveMode, bypassAllowed, kind) ||
                    (whitelistBypass && forced != VpnPath.Direct)
                val startDirectNow = (forced == VpnPath.Direct ||
                    shouldStartDirectWithoutDiagnostic(liveMode, kind, underlayUsable)) &&
                    forced != VpnPath.Bypass &&
                    !whitelistBypass
                val capturedMode = liveMode
                val capturedKind = kind
                val capturedProfileId = profile?.name
                val probePreferred = snap.probe?.preselectedPath
                val labelPreferred = when {
                    forced != null -> forced
                    liveMode == ConnPathMode.Direct -> VpnPath.Direct
                    liveMode == ConnPathMode.Bypass || skipProbe -> VpnPath.Bypass
                    wifiAutoDirect || startDirectNow -> VpnPath.Direct
                    else -> probePreferred ?: VpnPath.Direct
                }
                AppLog.v(TAG, "Connect requested mode=$liveMode preferred=$labelPreferred hideIp=${snap.hideIp}")
                EgressIpProbe.invalidate()
                _ui.value = snap.copy(
                    state = ConnState.Connecting,
                    statusText = "Подключение (${pathLabel(labelPreferred)})…",
                    connectEnabled = false,
                    lastError = null,
                )

                val deferHideIp =
                    snap.hideIp &&
                        (
                            skipProbe ||
                                startDirectNow ||
                                (
                                    shouldProvisionViaVpn(snap.probe) &&
                                        (labelPreferred == VpnPath.Bypass ||
                                            snap.probe?.preselectedPath == VpnPath.Bypass)
                                    )
                            )
                if (snap.hideIp && !deferHideIp) {
                    val r = syncHideIpToProvision(true, viaVpn = false)
                    if (r.isFailure) {
                        AppLog.e(TAG, "hide-ip enable failed: ${r.exceptionOrNull()?.message}")
                        pendingHideIpSync = true
                        AppLog.i(TAG, "Hide-IP enable deferred until tunnel up")
                    } else {
                        lastHideIpSent = true
                    }
                } else if (deferHideIp) {
                    pendingHideIpSync = true
                    AppLog.v(TAG, "Hide-IP deferred until Bypass tunnel (underlay cannot reach provision)")
                } else if (lastHideIpSent != false) {
                    val r = syncHideIpToProvision(false, viaVpn = false)
                    if (r.isSuccess) {
                        lastHideIpSent = false
                    } else {
                        pendingHideIpSync = true
                        AppLog.i(TAG, "Hide-IP disable deferred until tunnel up")
                    }
                }
                val selectedApps = runCatching { settingsRepo.excludedAppsSnapshot() }
                    .getOrDefault(emptySet())
                val whitelistOn = runCatching { settingsRepo.appsWhitelistModeSnapshot() }
                    .getOrDefault(false)
                var fresh: ProbeResult
                var usePath: VpnPath?
                if (skipProbe) {
                    AppLog.v(TAG, "Connect: skip VPS probe — Bypass immediately kind=$kind")
                    fresh = lastGood ?: ProbeResult(
                        networkClass = NetworkClass.NeedBypass,
                        preselectedPath = VpnPath.Bypass,
                        systemOnline = true,
                        yandexOk = true,
                        bigtechOk = false,
                        captive = false,
                        awgUdpOk = false,
                        provisionOk = false,
                        message = "Обход без зонда :9100",
                        elapsedMs = 0,
                    )
                    usePath = VpnPath.Bypass
                    AppLog.v(
                        TAG,
                        "Connect skip-probe use=Bypass mode=$pathMode kind=$kind " +
                            "whitelist=$whitelistOn apps=${selectedApps.size} " +
                            SplitTunnel.logSample(selectedApps),
                    )
                } else if (wifiAutoDirect || startDirectNow) {
                    AppLog.v(
                        TAG,
                        "Connect: skip diagnostic wait — start Direct kind=$kind " +
                            "wifiAuto=$wifiAutoDirect startNow=$startDirectNow",
                    )
                    if (wifiAutoDirect) {
                        scheduleCellularPreProbe("connected-wifi")
                    }
                    fresh = wifiAutoDirectProbe()
                    usePath = VpnPath.Direct
                    AppLog.v(
                        TAG,
                        "Connect skip-probe use=Direct mode=$pathMode kind=$kind " +
                            "whitelist=$whitelistOn apps=${selectedApps.size} " +
                            SplitTunnel.logSample(selectedApps),
                    )
                    launchBackgroundDiagnostic(
                        sessionEpoch = connectSessionEpoch,
                        networkEpoch = connectNetworkEpoch,
                    )
                } else {
                    fresh = NetworkProbe.probe(
                        appContext,
                        provisionUrl,
                        directEndpoint = directEndpoint,
                        bindNetwork = pickBestUnderlayNetwork(appContext),
                        quick = true,
                        onFastDecision = { fast ->
                            applyFastPathHint(
                                fast,
                                sessionEpoch = connectSessionEpoch,
                                networkEpoch = connectNetworkEpoch,
                                capturedNetworkKey = connectCapturedKey,
                                capturedProfileId = capturedProfileId,
                            )
                        },
                    )
                    AppLog.v(
                        TAG,
                        "Connect re-probe path=${fresh.preselectedPath} " +
                            "mode=$pathMode yandex=${fresh.yandexOk} " +
                            "cloudflare=${fresh.bigtechOk} vps=${fresh.provisionOk} " +
                            "kind=${currentAutoUnderlayKind()} " +
                            "whitelist=$whitelistOn apps=${selectedApps.size} " +
                            SplitTunnel.logSample(selectedApps),
                    )
                }
                val liveModeNow = pathMode
                val kindNow = currentAutoUnderlayKind()
                val bypassNow = callHashOrNull() != null
                if (
                    connectSnapshotChanged(
                        capturedMode,
                        liveModeNow,
                        capturedKind,
                        kindNow,
                        capturedProfileId,
                        profile?.name,
                    ) &&
                    liveModeNow == ConnPathMode.Auto &&
                    !shouldStartDirectWithoutDiagnostic(
                        liveModeNow,
                        kindNow,
                        readUnderlaySnapshot().availability == UnderlayAvailability.Usable,
                    )
                ) {
                    AppLog.v(TAG, "Connect: mode/underlay/profile changed during snapshot — probing")
                    fresh = NetworkProbe.probe(
                        appContext,
                        provisionUrl,
                        directEndpoint = directEndpoint,
                        bindNetwork = pickBestUnderlayNetwork(appContext),
                        quick = true,
                        onFastDecision = { fast ->
                            applyFastPathHint(
                                fast,
                                sessionEpoch = connectSessionEpoch,
                                networkEpoch = connectNetworkEpoch,
                                capturedNetworkKey = connectCapturedKey,
                                capturedProfileId = capturedProfileId,
                            )
                        },
                    )
                }
                usePath = forced ?: resolveConnectPath(
                    liveModeNow,
                    probePreferred,
                    lastGood,
                    fresh,
                    underlayKind = kindNow,
                    bypassAllowed = bypassNow,
                    underlayUsable = readUnderlaySnapshot().availability == UnderlayAvailability.Usable,
                    whitelistScorePercent = liveWhitelistEvidence()?.historicalWhitelistScore(
                        recoverySnapshot.underlay.key,
                        recoverySnapshot.intent.profileId,
                    ) ?: fresh.whitelistScorePercent,
                    freshStrongConfirmation = liveWhitelistEvidence()?.hasFreshStrong(
                        SystemClock.elapsedRealtime(),
                        recoverySnapshot.underlay.key,
                        recoverySnapshot.intent.profileId,
                    ) == true,
                )
                AppLog.v(
                    TAG,
                    "Connect resolved use=$usePath liveMode=$liveModeNow kind=$kindNow " +
                        "probe=${fresh.preselectedPath}",
                )
                if (usePath != null &&
                    liveModeNow == ConnPathMode.Auto &&
                    kindNow == UnderlayKind.Cellular &&
                    !startDirectNow &&
                    !wifiAutoDirect
                ) {
                    launchBackgroundDiagnostic(
                        sessionEpoch = connectSessionEpoch,
                        networkEpoch = connectNetworkEpoch,
                    )
                }
                if (usePath == null) {
                    AppLog.w(TAG, "Connect deferred — no path yet: ${fresh.message}")
                    endUserAttempt(fresh.message, keepReady = true)
                    applyProbe(
                        fresh,
                        sessionEpoch = connectSessionEpoch,
                        networkEpoch = connectNetworkEpoch,
                        capturedNetworkKey = connectCapturedKey,
                    )
                    return@launch
                }
                if (isDocumentationHost(directEndpoint) || isDocumentationHost(profile?.bypass?.peer)) {
                    AppLog.e(TAG, "Profile uses documentation IP — import JSON from VPS")
                    endUserAttempt("Профиль с документационным IP (203.0.113.x). Импортируйте JSON с VPS.")
                    _ui.value = _ui.value.copy(
                        probe = fresh,
                        softInfo = softInfoFor(fresh),
                    )
                    return@launch
                }
                if (usePath == VpnPath.Direct) {
                    val d = profile?.direct
                    if (d == null || d.privateKey.isBlank() || d.peerPublicKey.isBlank()) {
                        AppLog.e(TAG, "Direct: missing AWG keys in profile")
                        endUserAttempt("В профиле нет ключей AWG — нужен JSON с provision/smoke")
                        return@launch
                    }
                }
                if (usePath == VpnPath.Bypass && callHashOrNull().isNullOrBlank()) {
                    AppLog.e(TAG, "Bypass: call hash missing")
                    endUserAttempt("Для обхода необходимо сохранить код звонка на устройстве.")
                    _ui.value = _ui.value.copy(
                        probe = fresh,
                        softInfo = softInfoFor(fresh),
                    )
                    return@launch
                }
                startTunnel(usePath)
                AppLog.v(TAG, "Tunnel service start path=$usePath")
                val probeForUi = when {
                    usePath == VpnPath.Direct && fresh.preselectedPath != VpnPath.Direct ->
                        lastGood ?: fresh
                    else -> fresh
                }
                _ui.value = _ui.value.copy(
                    state = ConnState.Connecting,
                    activePath = usePath,
                    probe = probeForUi,
                    softInfo = softInfoFor(probeForUi),
                    statusText = "Запуск туннеля (${pathLabel(usePath)})…",
                    connectEnabled = false,
                )
            } catch (t: CancellationException) {
                AppLog.v(TAG, "Connect cancelled")
                throw t
            } catch (t: Throwable) {
                AppLog.e(TAG, "Connect crash: ${t.message ?: t.javaClass.simpleName}")
                endUserAttempt(t.message ?: "Сбой Connect")
            }
        }
    }

    fun reportUserError(message: String) {
        AppLog.w(TAG, message)
        endUserAttempt(message)
        _ui.value = _ui.value.copy(
            statusText = "Требуется действие",
            connectEnabled = connectAllowed(_ui.value.probe),
        )
    }

    private fun endUserAttempt(message: String, keepReady: Boolean = false) {
        tunnelStartSerializer.revokePending()
        connectRequests.finishAttempt()
        bumpSessionGeneration("attempt-failed")
        dispatchRecovery(
            ConnectionEvent.AttemptFailed(
                message = message,
                keepReady = keepReady,
            ),
        )
    }

    fun disconnect() {
        val state = _ui.value.state
        if (state == ConnState.Disconnecting) {
            AppLog.v(TAG, "Disconnect ignored: already disconnecting")
            return
        }
        recoveryTimer.clear()
        val revoke = connectRequests.revokeConnectWork()
        connectWaitJob?.cancel()
        connectWaitJob = null
        if (revoke.cancelProbe || probeJob?.isActive == true) {
            probeJob?.cancel()
            probeJob = null
        }
        diagnosticJob?.cancel()
        diagnosticJob = null
        val wantsConnected = recoverySnapshot.intent.wantsConnected
        val connectBusy = connectJob?.isActive == true
        // ConnState.Probing is the pre-intent wait. After UserConnect the UI
        // is Connecting, but Stop must still tear down intent if a race left
        // the label on Probing.
        val idleProbeOnly = state == ConnState.Probing && !wantsConnected && !connectBusy
        if (idleProbeOnly) {
            _ui.value = _ui.value.copy(
                state = if (_ui.value.probe != null) ConnState.Ready else ConnState.Idle,
                statusText = _ui.value.probe?.message ?: "Отменено",
                softInfo = softInfoFor(_ui.value.probe),
                connectEnabled = connectAllowed(_ui.value.probe),
                lastError = null,
            )
            AppLog.i(TAG, "Probe cancelled by user")
            return
        }
        if (
            !wantsConnected &&
            !connectBusy &&
            state != ConnState.Connected &&
            state != ConnState.Connecting &&
            state != ConnState.PausedTrustedWifi &&
            state != ConnState.WaitingForNetwork &&
            state != ConnState.Recovering &&
            state != ConnState.CaptivePortal &&
            state != ConnState.NeedsUserAction
        ) {
            return
        }
        stopWatchingUnderlay()
        tunnelStartSerializer.revokePending()
        dispatchRecovery(ConnectionEvent.UserDisconnect)
        softRestartInProgress = false
        handoverProbeStreak = ProbeStreak()
        lastHandoverBindHandle = null
        deadDirectBindHandle = null
        blockBypassToDirectUntilUnderlayChange = false
        callRecreateAttempts = 0
        callRecreateNetworkFails = 0
        callRecreateJob?.cancel()
        callRecreateJob = null
        presenceJob?.cancel()
        presenceJob = null
        connectJob?.cancel()
        connectJob = null
        runningNotifyJob?.cancel()
        runningNotifyJob = null
        val generation = bumpSessionGeneration("disconnect")
        transportRestartJob?.cancel()
        transportRestartJob = null
        AppLog.i(
            TAG,
            "Stop requested by=user gen=$generation session=${recoverySnapshot.sessionEpoch} " +
                "transport=${recoverySnapshot.transportEpoch} call=${recoverySnapshot.call.callEpoch}",
        )
        logTunnelLifecycle(
            "stop_requested",
            extra = JSONObject()
                .put("by", "user")
                .put("generation", generation)
                .put("session_epoch", recoverySnapshot.sessionEpoch)
                .put("transport_epoch", recoverySnapshot.transportEpoch)
                .put("call_epoch", recoverySnapshot.call.callEpoch),
        )
        // Flip state synchronously to avoid double-disconnect race on rapid taps.
        _ui.value = _ui.value.copy(
            state = ConnState.Disconnecting,
            statusText = "Отключение…",
            connectEnabled = false,
            lastError = null,
        )
        scope.launch {
            try {
                // Leave WARP policy as-is while hideIp stays on (next Connect reuses it).
                // If user turned hideIp off, clear server route.
                if (!_ui.value.hideIp) {
                    runCatching { syncHideIpToProvision(false, viaVpn = hideIpViaVpn()) }
                }
                stopTunnel()
                if (generation != sessionGeneration.get()) return@launch
                if (_ui.value.state != ConnState.Disconnecting) return@launch
                _ui.value = _ui.value.copy(
                    state = ConnState.Ready,
                    activePath = null,
                    statusText = _ui.value.probe?.message ?: "Готово",
                    softInfo = softInfoFor(_ui.value.probe),
                    connectEnabled = connectAllowed(_ui.value.probe),
                    lastError = null,
                    callRecreatePrompt = null,
                )
            } catch (_: CancellationException) {
                if (generation != sessionGeneration.get()) return@launch
                if (_ui.value.state != ConnState.Disconnecting) return@launch
                _ui.value = _ui.value.copy(
                    state = ConnState.Ready,
                    activePath = null,
                    statusText = _ui.value.probe?.message ?: "Готово",
                    softInfo = softInfoFor(_ui.value.probe),
                    connectEnabled = connectAllowed(_ui.value.probe),
                    lastError = null,
                    callRecreatePrompt = null,
                )
            } catch (t: Throwable) {
                if (generation != sessionGeneration.get()) return@launch
                if (_ui.value.state != ConnState.Disconnecting) return@launch
                val msg = t.message?.take(220) ?: t.javaClass.simpleName
                AppLog.e(TAG, "Disconnect failed: $msg")
                _ui.value = _ui.value.copy(
                    state = ConnState.Error,
                    activePath = null,
                    statusText = "Ошибка отключения",
                    lastError = msg,
                    connectEnabled = connectAllowed(_ui.value.probe),
                )
            }
        }
    }

    fun onServiceStarted(path: VpnPath) {
        Log.i(TAG, "VpnService started path=$path")
    }

    /** WDTT-Plus-style soft reconnect: keep Connected UI, show progress. */
    fun onTransportRestarting(reason: String) {
        bumpSessionGeneration("transport-restart")
        softRestartInProgress = true
        EgressIpProbe.invalidate()
        val path = _ui.value.activePath
        AppLog.v(TAG, "Transport soft restart: $reason")
        val status = when {
            reason.startsWith("Hide-IP") -> "Смена исходящего адреса. Выполняется повторное подключение…"
            reason.startsWith("[СЕТЬ]") -> "Сеть изменилась. Выполняется повторное подключение…"
            reason.startsWith("Переключение") ->
                "Выполняется переход на путь ${pathLabel(path ?: VpnPath.Direct)}…"
            else -> "Выполняется повторное подключение…"
        }
        _ui.value = _ui.value.copy(
            state = ConnState.Connecting,
            activePath = path,
            statusText = status,
            lastError = null,
            connectEnabled = true,
            softInfo = reason.removePrefix("[СЕТЬ] ").takeIf { it.isNotBlank() },
        )
        refreshVpnNotification()
    }

    /**
     * After Wi‑Fi↔LTE / SIM settle: re-classify underlay.
     * Open internet (Cloudflare TLS or UDP :53) + VPS /health → Direct.
     * Operator whitelist (Yandex up, Cloudflare TLS/UDP down) → Bypass
     * even if TCP :9100 answers (AWG is UDP).
     * NoNetwork → hold (do not restart into a dead SIM gap).
     *
     * [bindNetwork] must be the real underlay (NOT_VPN); probing through the
     * tunnel would falsely report Direct while on Bypass.
     */
    suspend fun decideNetworkHandover(
        bindNetwork: android.net.Network?,
        underlayChanged: Boolean = false,
        allowBypassToDirect: Boolean = true,
    ): NetworkHandoverDecision {
        val currentPath = TunnelSessionHolder.config?.path
            ?: _ui.value.activePath
            ?: return NetworkHandoverDecision.SoftRestartSamePath
        val mode = pathMode
        val bypassAllowed = hashStore.hasHash(profile?.name)
        val pathHealthy = currentPathLooksHealthy(currentPath)
        bindNetwork?.networkHandle?.let { lastHandoverBindHandle = it }
        val sameDeadUnderlay = deadDirectBindHandle != null &&
            bindNetwork?.networkHandle == deadDirectBindHandle
        if (underlayChanged && !sameDeadUnderlay) {
            deadDirectBindHandle = null
            blockBypassToDirectUntilUnderlayChange = false
        }
        val directFailedOnCurrentUnderlay = blockBypassToDirectUntilUnderlayChange &&
            (sameDeadUnderlay || deadDirectBindHandle == null || bindNetwork == null)
        val kind = underlayKindOf(bindNetwork)
        val bindEv = evidenceForBind(bindNetwork)
        val now = SystemClock.elapsedRealtime()
        val bindKey = keyForBind(bindNetwork)
        val whitelistLikely = RestrictionScore.bypassHoldsDirectReeval(
            historicalScore = bindEv?.historicalWhitelistScore(bindKey, recoverySnapshot.intent.profileId)
                ?: 0,
            freshStrong = bindEv?.hasFreshStrong(now, bindKey, recoverySnapshot.intent.profileId) == true,
            usable = bindEv?.usableAt(now, bindKey, recoverySnapshot.intent.profileId) == true,
            unknownStreak = bindEv?.unknownStreak ?: 0,
            alreadyBypass = currentPath == VpnPath.Bypass,
        )
        if (mode != ConnPathMode.Auto) {
            val decision = decideNetworkHandoverAction(
                pathMode = mode,
                currentPath = currentPath,
                probedPath = currentPath,
                bypassAllowed = bypassAllowed,
                sessionAgeMs = handoverSessionAgeMs(),
                currentPathHealthy = pathHealthy,
                underlayChanged = underlayChanged,
                allowBypassToDirect = allowBypassToDirect,
                directFailedOnCurrentUnderlay = directFailedOnCurrentUnderlay,
                underlayKind = kind,
            )
            AppLog.v(
                TAG,
                "Handover: mode=$mode path=$currentPath decision=$decision " +
                    "underlayChanged=$underlayChanged healthy=$pathHealthy (no re-probe)",
            )
            return decision
        }

        val skipWifiDirect = autoUsesDirectOnWifi(mode, kind)
        val skipWhitelistBypass = !skipWifiDirect &&
            kind == UnderlayKind.Cellular &&
            whitelistLikely &&
            bypassAllowed
        // An unmeasured cell must not be guessed as Direct: the quick probe is
        // cheaper than a dead-Direct cycle followed by a transport switch.
        val mustProbeCellular = shouldProbeCellularBeforeHandover(
            mode = mode,
            underlayKind = kind,
            bypassAllowed = bypassAllowed,
            hasScopedEvidence = evidenceForBind(bindNetwork) != null,
        )
        val skipDirectNow = !skipWifiDirect &&
            !skipWhitelistBypass &&
            !mustProbeCellular &&
            shouldStartDirectWithoutDiagnostic(mode, kind, underlayUsable = true)
        if (skipWifiDirect || skipDirectNow || skipWhitelistBypass) {
            val probed = if (skipWhitelistBypass) VpnPath.Bypass else VpnPath.Direct
            AppLog.v(
                TAG,
                "Handover: skip blocking diagnostic — Auto path=$currentPath " +
                    "kind=$kind probed=$probed whitelist=$whitelistLikely " +
                    "underlayChanged=$underlayChanged",
            )
            launchBackgroundDiagnostic(
                sessionEpoch = recoverySnapshot.sessionEpoch,
                networkEpoch = recoverySnapshot.networkEpoch,
            )
            if (skipWifiDirect) {
                scheduleCellularPreProbe("handover-wifi")
                // The reducer keeps a live Bypass until Wi‑Fi has held usable
                // for a whole settle window; its own Reeval timer performs the
                // upgrade once that passes. Tearing the call down here after a
                // flat settle would defeat that gate.
                if (currentPath == VpnPath.Bypass &&
                    recoverySnapshot.activePath == VpnPath.Bypass &&
                    recoverySnapshot.transport == TransportLifecycle.Running &&
                    wifiUpgradeStillSettling(
                        wifiUsableSinceMs = recoverySnapshot.wifiUsableSinceMs,
                        wifiFailStreak = recoverySnapshot.wifiFailStreak,
                        elapsedMs = SystemClock.elapsedRealtime(),
                    )
                ) {
                    AppLog.v(
                        TAG,
                        "Handover: Wi‑Fi still settling (streak=${recoverySnapshot.wifiFailStreak}) " +
                            "— leaving the live Bypass to the reducer re-check",
                    )
                    return NetworkHandoverDecision.NoAction
                }
            }
            val decision = decideNetworkHandoverAction(
                pathMode = mode,
                currentPath = currentPath,
                probedPath = probed,
                bypassAllowed = bypassAllowed,
                sessionAgeMs = handoverSessionAgeMs(),
                currentPathHealthy = pathHealthy,
                underlayVpsReachable = probed == VpnPath.Direct,
                sameProbeStreak = 1,
                underlayChanged = underlayChanged,
                allowBypassToDirect = allowBypassToDirect,
                directFailedOnCurrentUnderlay = directFailedOnCurrentUnderlay,
                underlayKind = kind,
                whitelistLikely = whitelistLikely,
            )
            applyHandoverDecisionUi(
                decision = decision,
                currentPath = currentPath,
                probePath = probed,
                probeMessage = null,
                bypassAllowed = bypassAllowed,
                underlayChanged = underlayChanged,
                vpsReachable = probed == VpnPath.Direct,
            )
            return decision
        }

        val base = resolveProvisionUrl()
        AppLog.v(
            TAG,
            "Handover probe start path=$currentPath mode=$mode endpoint=$directEndpoint " +
                "provision=$base bind=${bindNetwork?.networkHandle}",
        )
        val fresh = NetworkProbe.probe(
            context = appContext,
            provisionBaseUrl = base,
            directEndpoint = directEndpoint,
            bindNetwork = bindNetwork,
            quick = true,
        )
        AppLog.v(
            TAG,
            "Handover probe done class=${fresh.networkClass} path=${fresh.preselectedPath} " +
                "yandexDns=${fresh.yandexOk} cloudflare=${fresh.bigtechOk} " +
                "ru=${fresh.ruServiceOk} health=${fresh.provisionOk} ${fresh.elapsedMs}ms",
        )

        val liveMode = pathMode
        val livePath = TunnelSessionHolder.config?.path
            ?: _ui.value.activePath
            ?: currentPath
        val liveBypass = hashStore.hasHash(profile?.name)
        val liveKind = underlayKindOf(bindNetwork)
        // The stored score can be absent on a cell we have never measured; a
        // single clean round already reaches the enter threshold, so read it
        // back after the round has been folded in.
        fun measuredWhitelistLikely(): Boolean {
            val ev = liveWhitelistEvidence(bindKey)
            val now = SystemClock.elapsedRealtime()
            val score = ev?.historicalWhitelistScore(bindKey, recoverySnapshot.intent.profileId)
                ?: fresh.whitelistScorePercent
            val freshRoundStrong = RestrictionScore.sample(
                cellular = true,
                yandex = fresh.yandexOutcome,
                bigtech = fresh.bigtechOutcome,
                google = fresh.googleOutcome,
                ruService = fresh.ruServiceOutcome,
            ) == RestrictionSample.Positive
            return RestrictionScore.bypassHoldsDirectReeval(
                historicalScore = score,
                freshStrong = ev?.hasFreshStrong(now, bindKey, recoverySnapshot.intent.profileId) == true ||
                    freshRoundStrong,
                usable = ev?.usableAt(now, bindKey, recoverySnapshot.intent.profileId) == true ||
                    freshRoundStrong,
                unknownStreak = ev?.unknownStreak ?: 0,
                alreadyBypass = livePath == VpnPath.Bypass,
            )
        }
        if (!shouldApplyHandoverProbe(mode, liveMode, currentPath, livePath)) {
            AppLog.v(
                TAG,
                "Handover probe discarded — stale snapshot mode=$mode→$liveMode " +
                    "path=$currentPath→$livePath",
            )
            val liveDecision = decideNetworkHandoverAction(
                pathMode = liveMode,
                currentPath = livePath,
                probedPath = if (liveMode == ConnPathMode.Auto) fresh.preselectedPath else livePath,
                bypassAllowed = liveBypass,
                sessionAgeMs = handoverSessionAgeMs(),
                currentPathHealthy = currentPathLooksHealthy(livePath),
                underlayVpsReachable = fresh.provisionOk,
                sameProbeStreak = 1,
                underlayChanged = underlayChanged,
                allowBypassToDirect = allowBypassToDirect,
                directFailedOnCurrentUnderlay = directFailedOnCurrentUnderlay,
                underlayKind = liveKind,
                whitelistLikely = measuredWhitelistLikely(),
            )
            return liveDecision
        }
        stashHandoverProbe(fresh, bindKey)

        // Update UI probe snapshot without leaving Connected/Connecting.
        _ui.value = _ui.value.copy(
            probe = fresh,
            softInfo = softInfoFor(fresh),
            hasCallHash = hashStore.hasHash(profile?.name),
        )

        val vpsReachable = fresh.provisionOk
        if (underlayChanged) {
            handoverProbeStreak = ProbeStreak()
            AppLog.v(TAG, "Handover: underlay changed — reset probe streak")
        }
        handoverProbeStreak = updateProbeStreak(handoverProbeStreak, fresh.preselectedPath)
        val decision = decideNetworkHandoverAction(
            pathMode = liveMode,
            currentPath = livePath,
            probedPath = fresh.preselectedPath,
            bypassAllowed = liveBypass,
            sessionAgeMs = handoverSessionAgeMs(),
            currentPathHealthy = currentPathLooksHealthy(livePath),
            underlayVpsReachable = vpsReachable,
            sameProbeStreak = handoverProbeStreak.count,
            underlayChanged = underlayChanged,
            allowBypassToDirect = allowBypassToDirect,
            directFailedOnCurrentUnderlay = directFailedOnCurrentUnderlay,
            underlayKind = liveKind,
            whitelistLikely = measuredWhitelistLikely(),
        )
        applyHandoverDecisionUi(
            decision = decision,
            currentPath = currentPath,
            probePath = fresh.preselectedPath,
            probeMessage = fresh.message,
            bypassAllowed = bypassAllowed,
            underlayChanged = underlayChanged,
            vpsReachable = vpsReachable,
        )
        return decision
    }

    private fun applyHandoverDecisionUi(
        decision: NetworkHandoverDecision,
        currentPath: VpnPath,
        probePath: VpnPath?,
        probeMessage: String?,
        bypassAllowed: Boolean,
        underlayChanged: Boolean,
        vpsReachable: Boolean,
    ) {
        when (decision) {
            NetworkHandoverDecision.NoAction -> {
                AppLog.v(
                    TAG,
                    "Handover: no action path=$currentPath probe=$probePath " +
                        "vps=$vpsReachable streak=${handoverProbeStreak.count} " +
                        "underlayChanged=$underlayChanged " +
                        "connected=${_ui.value.state == ConnState.Connected}",
                )
            }
            is NetworkHandoverDecision.SwitchPath -> {
                AppLog.v(TAG, "Handover: switch $currentPath → ${decision.path}")
                applySessionPath(decision.path)
                softRestartInProgress = true
                _ui.value = _ui.value.copy(
                    state = ConnState.Connecting,
                    activePath = decision.path,
                    statusText = "Сеть изменилась. Выполняется переход на путь ${pathLabel(decision.path)}…",
                    softInfo = probeMessage,
                    lastError = null,
                    connectEnabled = true,
                )
            }
            NetworkHandoverDecision.SoftRestartSamePath -> {
                if (
                    probePath == VpnPath.Bypass &&
                    currentPath == VpnPath.Direct &&
                    !bypassAllowed
                ) {
                    AppLog.w(TAG, "Handover: need Bypass but no call hash — keep Direct soft-restart")
                } else {
                    AppLog.v(TAG, "Handover: keep $currentPath (probe=$probePath)")
                }
            }
            NetworkHandoverDecision.HoldWaitForNetwork -> {
                AppLog.v(TAG, "Handover: hold — нет устойчивой сети, ждём underlay")
                _ui.value = _ui.value.copy(
                    statusText = "Ожидание сети…",
                    softInfo = probeMessage?.takeIf { it.isNotBlank() }
                        ?: "Смена SIM/Wi‑Fi — ждём рабочий underlay.",
                    lastError = null,
                    connectEnabled = true,
                )
            }
        }
    }

    private fun underlayKindOf(network: Network?): UnderlayKind {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return UnderlayKind.Other
        val caps = network?.let { cm.getNetworkCapabilities(it) } ?: return UnderlayKind.Other
        return classifyUnderlayKind(
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
    }

    /** Auto must treat live Wi‑Fi as Wi‑Fi even if pickBest scored LTE higher. */
    private fun currentAutoUnderlayKind(): UnderlayKind = preferWifiUnderlayKind(
        hasValidatedWifi = hasValidatedWifiUnderlay(appContext),
        pickBestKind = underlayKindOf(pickBestUnderlayNetwork(appContext)),
        wifiConnected = readConnectedWifiState(appContext, requireBackground = false).connected,
    )

    private fun liveWhitelistEvidence(
        key: NetworkKey? = recoverySnapshot.underlay.key,
    ): ReachabilityEvidence? = whitelistEvidenceForUnderlay(
        recoverySnapshot.evidence,
        recoverySnapshot.cellularEvidence,
        key,
        recoverySnapshot.intent.profileId ?: profile?.name,
        nowElapsedMs = SystemClock.elapsedRealtime(),
    )

    private fun keyForBind(bindNetwork: Network?): NetworkKey? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        if (bindNetwork == null || cm == null) return recoverySnapshot.underlay.key
        val activeSub = activeCellularSubscriptionId(appContext)
        val sim = activeSub.takeIf {
            it != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        return networkKeyForNetwork(
            bindNetwork,
            cm,
            sim,
            carrier = cellularCarrierId(appContext, activeSub),
        )
    }

    private fun evidenceForBind(bindNetwork: Network?): ReachabilityEvidence? =
        liveWhitelistEvidence(keyForBind(bindNetwork))

    /**
     * Keep a handover round as evidence without running it through the reducer:
     * a ProbeFinished here can emit its own Start command while the service is
     * already acting on the decision this very probe produced.
     */
    private fun stashHandoverProbe(fresh: ProbeResult, key: NetworkKey?) {
        if (!WhitelistDetection.appliesTo(key)) return
        synchronized(recoveryGate) {
            val now = SystemClock.elapsedRealtime()
            val incoming = ReachabilityEvidence(
                networkKey = key,
                profileId = recoverySnapshot.intent.profileId,
                measuredAtElapsedMs = now,
                yandex = fresh.yandexOutcome,
                bigtech = fresh.bigtechOutcome,
                google = fresh.googleOutcome,
                ruService = fresh.ruServiceOutcome,
                provision = fresh.provisionOutcome,
                restriction = fresh.restriction,
                whitelistScorePercent = fresh.whitelistScorePercent,
                captive = fresh.captive,
                ttlUntilElapsedMs = now + RecoverySettings.PROBE_CACHE_TTL_MS,
                bindHandle = fresh.bindHandle,
                routeReason = fresh.routeReason,
                restrictionReason = fresh.restrictionReason,
                seriesId = fresh.seriesId,
            )
            val folded = foldReachabilityEvidence(
                previous = recoverySnapshot.cellularEvidence,
                incoming = incoming,
                cellular = true,
                elapsedMs = now,
            )
            val liveOnThisRadio =
                recoverySnapshot.underlay.key?.let { key?.matchesCellularUnderlay(it) } == true
            recoverySnapshot = recoverySnapshot.copy(
                cellularEvidence = folded,
                evidence = if (liveOnThisRadio) folded else recoverySnapshot.evidence,
            )
        }
    }

    private fun mobileDataEnabled(): Boolean = runCatching {
        val tm = appContext.getSystemService(TelephonyManager::class.java) ?: return false
        tm.isDataEnabled
    }.getOrDefault(true)

    private fun scheduleCellularPreProbe(reason: String) {
        val kind = currentAutoUnderlayKind()
        if (!shouldPreProbeCellular(pathMode, kind) || !mobileDataEnabled()) {
            releaseCellularRequest()
            return
        }
        if (cellularProbeJob?.isActive == true) return
        cellularProbeJob = scope.launch {
            runCellularPreProbe(reason)
        }
    }

    private fun requestCellularUnderlay() {
        if (cellularNetworkCallback != null) return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestedCellularNetwork = network
                scheduleCellularPreProbe("cellular-available")
            }

            override fun onLost(network: Network) {
                if (requestedCellularNetwork == network) requestedCellularNetwork = null
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val ok = runCatching {
            cm.requestNetwork(req, cb, Handler(Looper.getMainLooper()))
        }.onFailure { err ->
            AppLog.w(TAG, "cellular requestNetwork failed: ${err.message}")
        }.isSuccess
        if (ok) {
            cellularNetworkCallback = cb
            AppLog.i(TAG, "cellular underlay requested for pre-probe")
        }
    }

    private fun releaseCellularRequest() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        cellularNetworkCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        cellularNetworkCallback = null
        requestedCellularNetwork = null
    }

    private suspend fun runCellularPreProbe(reason: String) {
        if (!shouldPreProbeCellular(pathMode, currentAutoUnderlayKind())) {
            releaseCellularRequest()
            return
        }
        if (!mobileDataEnabled()) {
            releaseCellularRequest()
            return
        }
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val activeSub = activeCellularSubscriptionId(appContext)
        val bind = requestedCellularNetwork
            ?: pickCellularUnderlayNetwork(cm, activeSub)
        if (bind == null) {
            requestCellularUnderlay()
            AppLog.v(TAG, "cellular pre-probe wait for network ($reason)")
            return
        }
        val sim = activeSub.takeIf {
            it != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val key = networkKeyForNetwork(
            bind,
            cm,
            sim,
            carrier = cellularCarrierId(appContext, activeSub),
        )
        if (key == null || !key.isCellular) {
            AppLog.w(TAG, "cellular pre-probe bind is not cellular handle=${bind.networkHandle}")
            releaseCellularRequest()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val previousKey = recoverySnapshot.cellularEvidence?.measurementOrigin()
        if (previousKey != null && !whitelistOriginAllowsBind(previousKey, key) &&
            previousKey.restrictionScopeChanged(key)
        ) {
            lastCellularProbeAtMs = 0L
        }
        if (lastCellularProbeAtMs > 0L) {
            val waitMs = RecoverySettings.nextDiagnosticDelayMs(
                evidence = recoverySnapshot.cellularEvidence,
                nowElapsedMs = now,
                key = key,
                profileId = recoverySnapshot.intent.profileId,
            ) ?: RecoverySettings.DIAGNOSTIC_OPEN_INTERVAL_MS
            val since = now - lastCellularProbeAtMs
            if (since < waitMs && recoverySnapshot.cellularEvidence != null) {
                // Returning would end the refresh loop with no network request
                // registered and nothing to re-kick it — sit out the gap here.
                AppLog.v(TAG, "cellular pre-probe waits ${waitMs - since}ms ($reason)")
                delay(waitMs - since)
                if (shouldPreProbeCellular(pathMode, currentAutoUnderlayKind())) {
                    return runCellularPreProbe("refresh")
                }
                releaseCellularRequest()
                return
            }
        }
        AppLog.i(
            TAG,
            "cellular pre-probe start reason=$reason bind=${bind.networkHandle} sim=${key.simId}",
        )
        val result = NetworkProbe.probe(
            appContext,
            provisionUrl,
            directEndpoint = directEndpoint,
            bindNetwork = bind,
            quick = true,
        )
        lastCellularProbeAtMs = SystemClock.elapsedRealtime()
        val incoming = ReachabilityEvidence(
            networkKey = key,
            originNetworkKey = key,
            profileId = recoverySnapshot.intent.profileId ?: profile?.name,
            measuredAtElapsedMs = lastCellularProbeAtMs,
            yandex = result.yandexOutcome,
            bigtech = result.bigtechOutcome,
            google = result.googleOutcome,
            ruService = result.ruServiceOutcome,
            provision = result.provisionOutcome,
            restriction = result.restriction,
            whitelistScorePercent = result.whitelistScorePercent,
            captive = result.captive,
            ttlUntilElapsedMs = lastCellularProbeAtMs + RecoverySettings.PROBE_CACHE_TTL_MS,
            bindHandle = result.bindHandle ?: bind.networkHandle,
            routeReason = result.routeReason,
            restrictionReason = result.restrictionReason,
            seriesId = result.seriesId,
        )
        val reduced = dispatchRecovery(ConnectionEvent.CellularProbeFinished(incoming))
        AppLog.i(
            TAG,
            "cellular pre-probe done score=${reduced.state.cellularEvidence?.whitelistScorePercent} " +
                "restriction=${reduced.state.cellularEvidence?.restriction} " +
                "yandex=${result.yandexOutcome} cf=${result.bigtechOutcome} " +
                "google=${result.googleOutcome} ru=${result.ruServiceOutcome} ${result.elapsedMs}ms",
        )
        val delayMs = RecoverySettings.nextDiagnosticDelayMs(
            evidence = reduced.state.cellularEvidence,
            nowElapsedMs = lastCellularProbeAtMs,
            key = key,
            profileId = recoverySnapshot.intent.profileId,
        ) ?: RecoverySettings.DIAGNOSTIC_OPEN_INTERVAL_MS
        // Holding the request between rounds keeps the modem attached for the
        // whole Wi-Fi session; the next round re-acquires or re-requests it.
        releaseCellularRequest()
        delay(delayMs)
        if (shouldPreProbeCellular(pathMode, currentAutoUnderlayKind())) {
            runCellularPreProbe("refresh")
        }
    }

    /** Rewrite [TunnelSessionHolder] for a path switch mid-session (Auto handover). */
    fun applySessionPath(path: VpnPath) {
        val existing = TunnelSessionHolder.config ?: return
        val addr = when (path) {
            VpnPath.Direct -> profile?.direct?.address ?: existing.tunAddress
            VpnPath.Bypass -> profile?.bypass?.address ?: existing.tunAddress
        }
        TunnelSessionHolder.config = existing.copy(
            path = path,
            tunAddress = addr,
            callHash = callHashOrNull(),
            hideIp = _ui.value.hideIp,
            workers = workers,
            silentRecreate = silentRecreate,
            dialPathName = dialPath.name,
            callEpoch = recoverySnapshot.call.callEpoch,
        )
    }

    private fun currentPathLooksHealthy(path: VpnPath): Boolean = when (path) {
        VpnPath.Bypass -> TransportHealth.activeWorkers > 0
        VpnPath.Direct ->
            VpnLiveStats.totalRx > 0L ||
                VpnLiveStats.downBps > 0L ||
                RecoverySettings.directHandshakeLive(
                    handshakeSec = VpnLiveStats.currentAwgHandshakeSec(),
                    nowSec = System.currentTimeMillis() / 1000L,
                )
    }

    /** True when the live Direct attempt already proved useful delivery. */
    fun directPathConfirmed(): Boolean =
        recoverySnapshot.activePath == VpnPath.Direct &&
            recoverySnapshot.pathReadiness == PathReadiness.PathConfirmed

    /**
     * Data arrived on Direct after the PathConfirm window closed. Nothing else
     * re-confirms the attempt, so an idle connect stayed ProtocolReady forever
     * with a stale directNegative and a non-zero failureIndex blocking Auto.
     * A duplicate or stale call is dropped by the reducer's permit checks.
     */
    fun onDirectDataObserved() {
        if (TunnelSessionHolder.config?.path != VpnPath.Direct) return
        if (directPathConfirmed()) return
        AppLog.i(TAG, "Direct inbound data observed after confirm window — re-confirming path")
        dispatchRecovery(
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = recoverySnapshot.sessionEpoch,
                transportEpoch = recoverySnapshot.transportEpoch,
                networkKey = recoverySnapshot.underlay.key,
                pathConfirmed = true,
                protocolReady = true,
                callEpoch = recoverySnapshot.call.callEpoch,
            ),
        )
    }

    /**
     * Direct is Connected but TUN has no inbound bytes. Auto+hash on cellular
     * switches to Bypass; Auto on Wi‑Fi and forced Direct stop so the phone
     * is not a blackhole.
     */
    fun onDeadDirectNoRx() {
        val current = TunnelSessionHolder.config?.path ?: _ui.value.activePath
        if (current != VpnPath.Direct) return
        when (
            decideDeadDirectAction(
                pathMode = pathMode,
                bypassAllowed = callHashOrNull() != null,
                underlayKind = currentAutoUnderlayKind(),
            )
        ) {
            DeadDirectDecision.KeepWatching -> Unit
            DeadDirectDecision.SwitchToBypass -> {
                AppLog.w(TAG, "Dead Direct (no TUN rx) — Auto recovery → Bypass")
                handoverProbeStreak = ProbeStreak(VpnPath.Bypass, 1)
                deadDirectBindHandle = lastHandoverBindHandle
                blockBypassToDirectUntilUnderlayChange = true
                dispatchRecovery(
                    ConnectionEvent.DirectFailed(
                        sessionEpoch = recoverySnapshot.sessionEpoch,
                        transportEpoch = recoverySnapshot.transportEpoch,
                        networkKey = recoverySnapshot.underlay.key,
                        reason = "direct-no-rx",
                        callEpoch = recoverySnapshot.call.callEpoch,
                    ),
                )
            }
            DeadDirectDecision.FailSession -> {
                AppLog.w(TAG, "Dead Direct (no TUN rx) — recover or wait, no stopSelf by attempt count")
                dispatchRecovery(
                    ConnectionEvent.DirectFailed(
                        sessionEpoch = recoverySnapshot.sessionEpoch,
                        transportEpoch = recoverySnapshot.transportEpoch,
                        networkKey = recoverySnapshot.underlay.key,
                        reason = "direct-no-rx",
                        callEpoch = recoverySnapshot.call.callEpoch,
                    ),
                )
            }
        }
    }

    fun onUnderlyingNetworkLost() {
        if (!recoverySnapshot.intent.wantsConnected) return
        scope.launch {
            val snap = readUnderlaySnapshot()
            requestGoUpdateNetwork(snap)
            dispatchRecovery(ConnectionEvent.UnderlayUpdated(snap))
            scheduleCellularPreProbe("underlay-lost")
        }
    }

    fun onTrustedWifiWaiting(ssid: String) {
        softRestartInProgress = false
        dispatchRecovery(ConnectionEvent.TrustedWifiChanged(waiting = true))
        scope.launch {
            _ui.value = _ui.value.copy(
                state = ConnState.PausedTrustedWifi,
                statusText = "Туннель приостановлен в сети «$ssid»",
                softInfo = "При выходе из доверенной сети подключение будет восстановлено автоматически.",
                connectEnabled = true,
                lastError = null,
            )
        }
    }

    fun onTrustedWifiIdentifying() {
        if (_ui.value.state != ConnState.Connected && _ui.value.state != ConnState.Connecting) return
        scope.launch {
            _ui.value = _ui.value.copy(
                softInfo = "Определяется сеть Wi‑Fi. Прямое подключение не запускается до получения имени сети.",
            )
        }
    }

    fun onTrustedWifiSsidUnreadable(problem: TrustedWifiAccessProblem?) {
        if (_ui.value.state != ConnState.Connected && _ui.value.state != ConnState.Connecting) return
        val hint = when (problem) {
            TrustedWifiAccessProblem.ForegroundPermission ->
                "Не удалось определить имя сети Wi‑Fi. Предоставьте доступ к устройствам поблизости или к геолокации, иначе доверенная сеть не будет распознана."
            TrustedWifiAccessProblem.LocationDisabled ->
                "Включите геолокацию, чтобы определить доверенную сеть Wi‑Fi."
            TrustedWifiAccessProblem.BackgroundPermission ->
                "Для приостановки туннеля в фоне требуется разрешение геолокации «Всегда»."
            null ->
                "Не удалось определить имя сети Wi‑Fi. Текущий маршрут сохранён."
        }
        scope.launch {
            _ui.value = _ui.value.copy(softInfo = hint)
        }
    }

    fun onTrustedWifiResuming() {
        dispatchRecovery(ConnectionEvent.TrustedWifiChanged(waiting = false))
        scope.launch {
            _ui.value = _ui.value.copy(
                state = ConnState.Connecting,
                statusText = "Выход из доверенной сети. Выполняется подключение…",
                softInfo = null,
                connectEnabled = true,
                lastError = null,
            )
        }
    }

    fun onTunnelRunning(path: VpnPath) {
        val generation = sessionGeneration.get()
        val sessionEpoch = recoverySnapshot.sessionEpoch
        val transportEpoch = recoverySnapshot.transportEpoch
        val callEpoch = recoverySnapshot.call.callEpoch
        val networkKey = recoverySnapshot.underlay.key
        // Cancel only our previous verifier for this session; a newer generation
        // owns its own job and must not be cleared by a stale callback.
        val previousJob = runningNotifyJob
        runningNotifyJob = scope.launch {
            if (generation != sessionGeneration.get()) return@launch
            softRestartInProgress = false
            var pathConfirmed = false
            var protocolReady = false
            var backendRunning = false
            var earlyEmitted = false
            suspend fun emitPartial(verdict: PathConfirmVerdict) {
                if (earlyEmitted) return
                if (verdict != PathConfirmVerdict.ProtocolReady &&
                    verdict != PathConfirmVerdict.BackendRunning &&
                    verdict != PathConfirmVerdict.PathConfirmed
                ) {
                    return
                }
                earlyEmitted = true
                val pc = verdict == PathConfirmVerdict.PathConfirmed
                val pr = verdict == PathConfirmVerdict.ProtocolReady || pc
                val br = PathConfirm.bypassMayConnect(verdict)
                if (generation != sessionGeneration.get()) return
                val confirmed = if (path == VpnPath.Direct) {
                    dispatchRecovery(
                        ConnectionEvent.DirectConfirmed(
                            sessionEpoch = sessionEpoch,
                            transportEpoch = transportEpoch,
                            networkKey = networkKey,
                            probeConfirmed = false,
                            pathConfirmed = pc,
                            protocolReady = pr,
                            callEpoch = callEpoch,
                        ),
                    )
                } else {
                    dispatchRecovery(
                        ConnectionEvent.BypassConfirmed(
                            sessionEpoch = sessionEpoch,
                            transportEpoch = transportEpoch,
                            probeConfirmed = false,
                            pathConfirmed = pc,
                            protocolReady = pr,
                            backendRunning = br,
                            callEpoch = callEpoch,
                        ),
                    )
                }
                if (!acceptedLiveTunnelConfirm(confirmed)) {
                    AppLog.w(TAG, "Partial tunnel confirm ignored path=$path")
                    return
                }
                _ui.value = _ui.value.copy(
                    state = ConnState.Connected,
                    activePath = path,
                    statusText = when {
                        pc && path == VpnPath.Direct -> "Подключено: прямое" + hideSuffix()
                        pc && path == VpnPath.Bypass -> "Подключено: обход" + hideSuffix()
                        pr && path == VpnPath.Direct ->
                            "Прямое подключение установлено. Ожидаем обмен данными…"
                        path == VpnPath.Bypass ->
                            "Обход запущен. Ожидаем обмен данными…"
                        else -> "Подключение…"
                    },
                    connectEnabled = true,
                    lastError = null,
                    softInfo = softInfoFor(_ui.value.probe),
                )
            }
            if (path == VpnPath.Bypass) {
                _ui.value = _ui.value.copy(
                    state = ConnState.Connecting,
                    activePath = path,
                    statusText = "Ожидание каналов обхода…",
                    connectEnabled = true,
                    lastError = null,
                    softInfo = softInfoFor(_ui.value.probe),
                )
                val verdict = waitForBypassPathConfirm(
                    generation,
                    sessionEpoch,
                    transportEpoch,
                    networkKey,
                    callEpoch,
                    onPartial = { emitPartial(it) },
                )
                if (generation != sessionGeneration.get()) return@launch
                if (verdict == PathConfirmVerdict.Stale) {
                    // Stale only if a successor owns the attempt; otherwise fail closed.
                    if (recoverySnapshot.transportEpoch == transportEpoch &&
                        recoverySnapshot.recovery.inFlight
                    ) {
                        AppLog.w(TAG, "Bypass confirm stale without successor — failing attempt")
                        onTunnelFailed("Обход недоступен: устаревшая попытка подтверждения.")
                    }
                    return@launch
                }
                if (!PathConfirm.bypassMayConnect(verdict)) {
                    AppLog.e(TAG, "Bypass path wait verdict=$verdict after ${BYPASS_WORKERS_WAIT_MS}ms")
                    val msg = if (verdict == PathConfirmVerdict.WriteFailed) {
                        "Обход недоступен: ошибка записи в TUN."
                    } else {
                        "Обход недоступен: нет активных каналов. Проверьте код звонка и сеть."
                    }
                    onTunnelFailed(msg)
                    return@launch
                }
                pathConfirmed = verdict == PathConfirmVerdict.PathConfirmed
                protocolReady = verdict == PathConfirmVerdict.ProtocolReady || pathConfirmed
                backendRunning = PathConfirm.bypassMayConnect(verdict)
            }
            if (generation != sessionGeneration.get()) return@launch
            if (path == VpnPath.Direct) {
                val verdict = waitForDirectPathConfirm(
                    generation,
                    sessionEpoch,
                    transportEpoch,
                    networkKey,
                    callEpoch,
                    onPartial = { emitPartial(it) },
                )
                if (generation != sessionGeneration.get()) return@launch
                if (verdict == PathConfirmVerdict.Stale) {
                    if (recoverySnapshot.transportEpoch == transportEpoch &&
                        recoverySnapshot.recovery.inFlight
                    ) {
                        AppLog.w(TAG, "Direct confirm stale without successor — failing attempt")
                        dispatchRecovery(
                            ConnectionEvent.DirectFailed(
                                sessionEpoch = sessionEpoch,
                                transportEpoch = transportEpoch,
                                networkKey = networkKey,
                                reason = "direct-stale-confirm",
                                callEpoch = callEpoch,
                            ),
                        )
                    }
                    return@launch
                }
                if (!PathConfirm.directMayConnect(verdict)) {
                    dispatchRecovery(
                        ConnectionEvent.DirectFailed(
                            sessionEpoch = sessionEpoch,
                            transportEpoch = transportEpoch,
                            networkKey = networkKey,
                            reason = "direct-no-path-confirm",
                            callEpoch = callEpoch,
                        ),
                    )
                    return@launch
                }
                pathConfirmed = verdict == PathConfirmVerdict.PathConfirmed
                protocolReady = verdict == PathConfirmVerdict.ProtocolReady || pathConfirmed
            }
            val confirmed = if (path == VpnPath.Direct) {
                dispatchRecovery(
                    ConnectionEvent.DirectConfirmed(
                        sessionEpoch = sessionEpoch,
                        transportEpoch = transportEpoch,
                        networkKey = networkKey,
                        probeConfirmed = false,
                        pathConfirmed = pathConfirmed,
                        protocolReady = protocolReady,
                        callEpoch = callEpoch,
                    ),
                )
            } else {
                dispatchRecovery(
                    ConnectionEvent.BypassConfirmed(
                        sessionEpoch = sessionEpoch,
                        transportEpoch = transportEpoch,
                        probeConfirmed = false,
                        pathConfirmed = pathConfirmed,
                        protocolReady = protocolReady,
                        backendRunning = backendRunning,
                        callEpoch = callEpoch,
                    ),
                )
            }
            if (!acceptedLiveTunnelConfirm(confirmed)) {
                AppLog.w(TAG, "Tunnel running ignored — confirm rejected path=$path")
                return@launch
            }
            _ui.value = _ui.value.copy(
                state = ConnState.Connected,
                activePath = path,
                statusText = when {
                    pathConfirmed && path == VpnPath.Direct -> "Подключено: прямое" + hideSuffix()
                    pathConfirmed && path == VpnPath.Bypass -> "Подключено: обход" + hideSuffix()
                    protocolReady && path == VpnPath.Direct ->
                        "Прямое подключение установлено. Ожидаем обмен данными…"
                    path == VpnPath.Bypass ->
                        "Обход запущен. Ожидаем обмен данными…"
                    else -> when (path) {
                        VpnPath.Direct -> "Подключено: прямое" + hideSuffix()
                        VpnPath.Bypass -> "Подключено: обход" + hideSuffix()
                    }
                },
                connectEnabled = true,
                lastError = null,
                callRecreatePrompt = null,
                softInfo = softInfoFor(_ui.value.probe),
            )
            if (path == VpnPath.Bypass) {
                callRecreateAttempts = 0
            }
            refreshVpnNotification()
            if (generation != sessionGeneration.get()) return@launch
            val wantHideIp = _ui.value.hideIp
            if (hideIpShouldRetryAfterTunnel(lastHideIpSent, wantHideIp, pendingHideIpSync)) {
                pendingHideIpSync = false
                val r = syncHideIpToProvision(wantHideIp, viaVpn = true, tryVpnFallback = false)
                if (r.isSuccess) {
                    lastHideIpSent = wantHideIp
                } else {
                    pendingHideIpSync = true
                    AppLog.e(TAG, "hide-ip post-tunnel sync failed: ${r.exceptionOrNull()?.message}")
                    if (wantHideIp) {
                        _ui.value = _ui.value.copy(
                            lastError = "Скрытие адреса: ${r.exceptionOrNull()?.message}",
                        )
                    }
                }
            }
            if (generation != sessionGeneration.get()) return@launch
            scheduleEgressIpRefresh("tunnel-up")
            schedulePresenceHeartbeat()
        }
        previousJob?.cancel()
    }

    fun onParkedBypassDied() {
        dispatchRecovery(
            ConnectionEvent.ParkedProcessDied(sessionEpoch = recoverySnapshot.sessionEpoch),
        )
    }

    /**
     * @return what VpnTunnelService should do instead of blindly stopping.
     */
    fun onTunnelFailed(message: String): TunnelFailureAction {
        if (
            message.contains("cancelled", ignoreCase = true) ||
            message.contains("StandaloneCoroutine", ignoreCase = true)
        ) {
            AppLog.w(TAG, "Ignoring cancel as tunnel failure: $message")
            return TunnelFailureAction.Ignore
        }
        val failedPath = TunnelSessionHolder.config?.path ?: _ui.value.activePath
        val sessionEpoch = recoverySnapshot.sessionEpoch
        val transportEpoch = recoverySnapshot.transportEpoch
        val callEpoch = recoverySnapshot.call.callEpoch
        if (failedPath == VpnPath.Bypass && isDeadCallMessage(message)) {
            return onDeadCallFailed(message)
        }
        val bypassAction = if (failedPath == VpnPath.Bypass) {
            userActionForBypassFailure(message)
        } else {
            null
        }
        val canFallback =
            autoMayUseBypass(
                pathMode,
                currentAutoUnderlayKind(),
                callHashOrNull() != null,
            ) &&
                failedPath == VpnPath.Direct &&
                _ui.value.state != ConnState.Disconnecting &&
                _ui.value.state != ConnState.Ready
        if (canFallback) {
            AppLog.i(TAG, "Direct failed — Auto recovery may start Bypass: $message")
            dispatchRecovery(
                ConnectionEvent.DirectFailed(
                    sessionEpoch = recoverySnapshot.sessionEpoch,
                    transportEpoch = recoverySnapshot.transportEpoch,
                    networkKey = recoverySnapshot.underlay.key,
                    reason = message,
                    callEpoch = callEpoch,
                ),
            )
            return TunnelFailureAction.KeepRecovering
        }
        val failed = dispatchRecovery(
            if (failedPath == VpnPath.Bypass) {
                ConnectionEvent.BypassFailed(
                    sessionEpoch = sessionEpoch,
                    transportEpoch = transportEpoch,
                    reason = message,
                    userAction = bypassAction,
                    callEpoch = callEpoch,
                )
            } else {
                ConnectionEvent.DirectFailed(
                    sessionEpoch = sessionEpoch,
                    transportEpoch = transportEpoch,
                    networkKey = recoverySnapshot.underlay.key,
                    reason = message,
                    callEpoch = callEpoch,
                )
            },
        )
        if (failed.state.intent.wantsConnected) {
            AppLog.i(TAG, "Temporary tunnel failure — recovering: $message")
            return TunnelFailureAction.KeepRecovering
        }
        failToError(message)
        return TunnelFailureAction.Stop
    }

    fun dismissCallRecreatePrompt() {
        _ui.value = _ui.value.copy(callRecreatePrompt = null)
    }

    /** User confirmed a new VK call from the Tunnel dialog. */
    fun confirmCallRecreate() {
        startCallRecreate(holdService = false)
    }

    private fun startCallRecreate(holdService: Boolean) {
        val update = decideCallUpdate(
            validity = CallValidity.ConfirmedDead,
            underlayAllowsOps = recoverySnapshot.underlay.allowsNetworkOps,
            userRequestedNew = !holdService,
            autoRecreate = silentRecreate,
            createInFlight = callRecreateJob?.isActive == true,
        )
        if (update == CallUpdateDecision.WaitForNetwork) {
            AppLog.w(TAG, "Call recreate deferred — no underlay")
            watchUnderlayUntilUsable()
            return
        }
        if (update == CallUpdateDecision.WaitUserAction) {
            AppLog.w(TAG, "Call recreate already in progress or needs user")
            return
        }
        if (!shouldCreateNewCall(
                ipChanged = false,
                simChanged = false,
                networkHandleChanged = false,
                timeout = false,
                turnQuota = false,
                staleNonce = false,
                allocationMismatch = false,
                anonymTokenOutdated = false,
                credentialsExpired = false,
                callConfirmedDead = true,
                userRequested = !holdService,
            ) && update != CallUpdateDecision.CreateNewCall
        ) {
            return
        }
        if (callRecreateJob?.isActive == true) {
            AppLog.w(TAG, "Call recreate already in progress")
            return
        }
        callRecreateJob = scope.launch {
            try {
                recreateCallThenReconnect(holdService = holdService)
            } catch (e: CancellationException) {
                AppLog.i(TAG, "Call recreate cancelled hold=$holdService")
                logTunnelLifecycle(
                    "call_recreate",
                    JSONObject().put("decision", "cancelled").put("hold", holdService),
                )
                throw e
            } catch (t: Throwable) {
                TelemetryBridge.handledError("call_recreate", t)
                AppLog.e(TAG, "Call recreate handled: ${t.message ?: t.javaClass.simpleName}")
                applyCallRecreateOutcome(
                    captured = currentRecreateIdentity(),
                    outcome = CallHashOutcome.Failure(
                        classifyCallHashThrowable(t, CallHashPhase.OAuth),
                    ),
                    holdService = holdService,
                )
            }
        }
    }

    private fun onDeadCallFailed(message: String): TunnelFailureAction {
        val action = decideDeadCallAction(
            silentRecreate = silentRecreate,
            hasVkSession = VkSession.hasSessionCookie(),
            recreateAttempts = callRecreateAttempts,
        )
        AppLog.i(TAG, "Dead VK call action=$action attempts=$callRecreateAttempts silent=$silentRecreate")
        val sessionEpoch = recoverySnapshot.sessionEpoch
        return when (action) {
            DeadCallAction.SilentRecreate -> {
                startCallRecreate(holdService = true)
                TunnelFailureAction.HoldForCallRecreate
            }
            DeadCallAction.AskUser -> {
                dispatchRecovery(
                    ConnectionEvent.CallValidityChanged(
                        sessionEpoch = sessionEpoch,
                        validity = CallValidity.ConfirmedDead,
                    ),
                )
                _ui.value = _ui.value.copy(callRecreatePrompt = CallRecreatePrompt.Ask)
                TunnelFailureAction.KeepRecovering
            }
            DeadCallAction.NeedVkLogin -> {
                dispatchRecovery(
                    ConnectionEvent.CallValidityChanged(
                        sessionEpoch = sessionEpoch,
                        validity = CallValidity.NeedsAuth,
                    ),
                )
                _ui.value = _ui.value.copy(callRecreatePrompt = CallRecreatePrompt.NeedLogin)
                TunnelFailureAction.KeepRecovering
            }
            DeadCallAction.GiveUp -> {
                dispatchRecovery(
                    ConnectionEvent.CallValidityChanged(
                        sessionEpoch = sessionEpoch,
                        validity = CallValidity.ConfirmedDead,
                    ),
                )
                TunnelFailureAction.KeepRecovering
            }
        }
    }

    private fun currentRecreateIdentity(): CallRecreateIdentity {
        return CallRecreateIdentity(
            sessionEpoch = recoverySnapshot.sessionEpoch,
            generation = sessionGeneration.get(),
            profileId = recoverySnapshot.intent.profileId ?: profile?.name,
            callEpoch = recoverySnapshot.call.callEpoch,
            requestId = connectRequests.activeRequestId(),
            wantsConnected = recoverySnapshot.intent.wantsConnected,
        )
    }

    private suspend fun recreateCallThenReconnect(
        holdService: Boolean,
        countDeadAttempt: Boolean = true,
    ) {
        val captured = currentRecreateIdentity()
        if (!captured.wantsConnected) {
            AppLog.i(TAG, "Call recreate skipped — wantsConnected=false")
            return
        }
        if (countDeadAttempt) callRecreateAttempts++
        _ui.value = _ui.value.copy(
            state = ConnState.Connecting,
            activePath = VpnPath.Bypass,
            statusText = if (holdService) "Обновляю звонок…" else "Создаю новый звонок…",
            lastError = null,
            callRecreatePrompt = null,
            connectEnabled = true,
            softInfo = "Нужна сессия ВКонтакте на этом устройстве.",
        )
        logTunnelLifecycle(
            "call_recreate",
            JSONObject()
                .put("phase", "start")
                .put("hold", holdService)
                .put("network_fails", callRecreateNetworkFails)
                .put("attempts", callRecreateAttempts),
        )
        if (!VkSession.hasSessionCookie()) {
            val login = try {
                VkLoginActivity.login(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            if (login.isFailure || !VkSession.hasSessionCookie()) {
                val failure = login.exceptionOrNull()?.let {
                    classifyCallHashThrowable(it, CallHashPhase.Session)
                } ?: CallHashFailure(
                    kind = CallHashErrorKind.AuthRequired,
                    phase = CallHashPhase.Session,
                    message = "Авторизация ВКонтакте не выполнена.",
                )
                applyCallRecreateOutcome(
                    captured = captured,
                    outcome = CallHashOutcome.Failure(failure),
                    holdService = holdService,
                )
                return
            }
        }
        val outcome = try {
            VkCallHashGenerator.generateOutcome(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            TelemetryBridge.handledError("call_recreate_generate", t)
            CallHashOutcome.Failure(classifyCallHashThrowable(t, CallHashPhase.OAuth))
        }
        applyCallRecreateOutcome(captured, outcome, holdService)
    }

    private fun applyCallRecreateOutcome(
        captured: CallRecreateIdentity,
        outcome: CallHashOutcome,
        holdService: Boolean,
    ) {
        val live = currentRecreateIdentity()
        val decision = evaluateCallRecreateResult(
            captured = captured,
            live = live,
            outcome = outcome,
            networkAttempts = callRecreateNetworkFails,
        )
        val plan = planCallRecreateApply(
            decision = decision,
            underlayAllowsOps = recoverySnapshot.underlay.allowsNetworkOps,
            silentRecreateInFlight = holdService && silentRecreate,
        )
        val kind = (outcome as? CallHashOutcome.Failure)?.error?.kind?.name
        val phase = (outcome as? CallHashOutcome.Failure)?.error?.phase?.name
        val apiCode = (outcome as? CallHashOutcome.Failure)?.error?.apiCode
        val httpCode = (outcome as? CallHashOutcome.Failure)?.error?.httpCode
        AppLog.i(
            TAG,
            "Call recreate decision=${plan.decisionName} kind=$kind phase=$phase " +
                "api=$apiCode http=$httpCode keep=${plan.keepWantsConnected} stop=${plan.stopTunnel}",
        )
        logTunnelLifecycle(
            "call_recreate",
            JSONObject()
                .put("decision", plan.decisionName)
                .put("kind", kind ?: JSONObject.NULL)
                .put("phase", phase ?: JSONObject.NULL)
                .put("api_code", apiCode ?: JSONObject.NULL)
                .put("http_code", httpCode ?: JSONObject.NULL)
                .put("retry_ms", plan.retryDelayMs ?: JSONObject.NULL)
                .put("hold", holdService),
        )
        when {
            plan.saveHash != null && plan.reconnectBypass -> {
                if (!callRecreateIdentityStillCurrent(captured, currentRecreateIdentity())) {
                    AppLog.i(TAG, "Call recreate hash ignored — session changed")
                    return
                }
                callRecreateNetworkFails = 0
                saveCallHash(plan.saveHash)
                applySessionPath(VpnPath.Bypass)
                AppLog.i(TAG, "Saved recreated call hash, restarting Bypass hold=$holdService")
                if (holdService && TunnelSessionHolder.config != null) {
                    requestTransportRestart("Новый код звонка", pathOverride = VpnPath.Bypass)
                } else {
                    _ui.value = _ui.value.copy(
                        state = ConnState.Ready,
                        lastError = null,
                        callRecreatePrompt = null,
                        connectEnabled = true,
                    )
                    endUserAttempt(_ui.value.statusText.ifBlank { "Отключено" }, keepReady = true)
                    connect()
                }
            }
            plan.retryDelayMs != null -> {
                callRecreateNetworkFails++
                plan.uiState?.let { state ->
                    _ui.value = _ui.value.copy(
                        state = state,
                        statusText = plan.status ?: "Нет связи с ВКонтакте",
                        lastError = plan.status,
                        callRecreatePrompt = null,
                        connectEnabled = true,
                    )
                }
                plan.dispatchValidity?.let { validity ->
                    dispatchRecovery(
                        ConnectionEvent.CallValidityChanged(
                            sessionEpoch = recoverySnapshot.sessionEpoch,
                            validity = validity,
                        ),
                    )
                }
                val delayMs = plan.retryDelayMs
                callRecreateJob = scope.launch {
                    delay(delayMs)
                    if (!callRecreateIdentityStillCurrent(captured, currentRecreateIdentity())) {
                        AppLog.v(TAG, "Call recreate retry skipped — session changed")
                        return@launch
                    }
                    recreateCallThenReconnect(holdService = holdService, countDeadAttempt = false)
                }
            }
            plan.decisionName == "ignore_stale" || plan.decisionName == "ignore_cancelled" -> {
                AppLog.i(TAG, "Call recreate ignored (${plan.decisionName})")
            }
            else -> {
                callRecreateNetworkFails = 0
                _ui.value = _ui.value.copy(
                    state = plan.uiState ?: ConnState.NeedsUserAction,
                    statusText = plan.status ?: "Не удалось обновить звонок",
                    lastError = plan.status,
                    callRecreatePrompt = plan.prompt,
                    connectEnabled = connectAllowed(_ui.value.probe),
                )
                plan.dispatchValidity?.let { validity ->
                    dispatchRecovery(
                        ConnectionEvent.CallValidityChanged(
                            sessionEpoch = recoverySnapshot.sessionEpoch,
                            validity = validity,
                        ),
                    )
                }
                if (plan.stopTunnel && holdService) {
                    scope.launch { stopTunnel() }
                }
                if (plan.endUserAttempt) {
                    endUserAttempt(plan.status ?: "Не удалось обновить звонок")
                }
                refreshVpnNotification()
            }
        }
    }

    private fun failToError(
        message: String,
        prompt: CallRecreatePrompt? = null,
        status: String? = null,
    ) {
        val wasSoft = softRestartInProgress
        softRestartInProgress = false
        val cur = _ui.value
        if (cur.state == ConnState.Disconnecting) {
            AppLog.w(TAG, "Ignoring late tunnel failure in Disconnecting: $message")
            return
        }
        if (cur.state == ConnState.Ready && prompt == null) {
            AppLog.w(TAG, "Ignoring late tunnel failure in Ready: $message")
            return
        }
        // Set Error before stopSelf/onDestroy can flip Connecting → Ready and drop the dialog.
        val statusText = status
            ?: if (wasSoft) "Не удалось переподключиться" else "Ошибка подключения"
        endUserAttempt(message)
        _ui.value = _ui.value.copy(
            statusText = statusText,
            lastError = message,
            callRecreatePrompt = prompt,
            connectEnabled = connectAllowed(cur.probe),
        )
        scope.launch { stopTunnel() }
    }

    fun onServiceStopped(owner: Long, releaseStartGate: Boolean = true) {
        val dispatched = dispatchServiceStopped(
            requests = connectRequests,
            serializer = tunnelStartSerializer,
            owner = owner,
            releaseStartGate = releaseStartGate,
            softRestart = softRestartInProgress,
        )
        val cause = when {
            vpnPermissionRevoked -> "revoke"
            softRestartInProgress -> "soft_restart"
            _ui.value.state == ConnState.Disconnecting -> "user_stop"
            !recoverySnapshot.intent.wantsConnected -> "expected"
            else -> "unexpected"
        }
        val line =
            "Service stopped cause=$cause owner=$owner finish=${dispatched.finishLiveAttempt} " +
                "wants=${recoverySnapshot.intent.wantsConnected} gen=${sessionGeneration.get()}"
        if (cause == "unexpected") {
            AppLog.i(TAG, "Unexpected VPN service stop. $line")
        } else {
            AppLog.i(TAG, line)
        }
        logTunnelLifecycle(
            "service_stopped",
            JSONObject()
                .put("cause", cause)
                .put("owner", owner)
                .put("finish_attempt", dispatched.finishLiveAttempt)
                .put("deferred_ticket", dispatched.deferredStart?.ticket ?: JSONObject.NULL),
            verbose = cause != "unexpected" && cause != "revoke",
        )
        if (softRestartInProgress) {
            AppLog.v(TAG, "Ignoring service stopped during soft restart")
            postDeferredTunnelStart(dispatched.deferredStart)
            return
        }
        if (!dispatched.finishLiveAttempt) {
            AppLog.v(TAG, "Ignoring stale service stop owner=$owner")
            postDeferredTunnelStart(dispatched.deferredStart)
            return
        }
        bumpSessionGeneration("service-stopped")
        val cur = _ui.value
        if (
            cur.state == ConnState.Connected ||
            cur.state == ConnState.Connecting ||
            cur.state == ConnState.PausedTrustedWifi
        ) {
            _ui.value = cur.copy(
                state = ConnState.Ready,
                activePath = null,
                statusText = when (cause) {
                    "revoke" -> "Система отозвала разрешение VPN"
                    "unexpected" -> "VPN-сервис остановился"
                    else -> cur.probe?.message ?: "Отключено"
                },
                softInfo = softInfoFor(cur.probe),
                connectEnabled = connectAllowed(cur.probe),
            )
        }
        val keepReady = _ui.value.state != ConnState.Error &&
            _ui.value.state != ConnState.Disconnecting
        connectRequests.finishAttempt()
        if (
            recoverySnapshot.intent.wantsConnected &&
            !vpnPermissionRevoked &&
            cause != "user_stop"
        ) {
            dispatchRecovery(
                ConnectionEvent.AttemptFailed(
                    message = _ui.value.lastError ?: _ui.value.statusText.ifBlank { "Отключено" },
                    keepReady = keepReady,
                ),
            )
        }
        runCatching {
            com.ardtt.app.TunnelWidgetProvider.updateWidgetState(
                appContext,
                running = false,
                statsText = null,
            )
            com.ardtt.app.QuickToggleTileService.requestTileUpdate(appContext)
            com.ardtt.app.AppShortcuts.refreshAsync(appContext)
        }
        postDeferredTunnelStart(dispatched.deferredStart)
    }

    fun onVpnPermissionRevoked(owner: Long) {
        vpnPermissionRevoked = true
        tunnelStartSerializer.revokePending()
        callRecreateJob?.cancel()
        callRecreateJob = null
        callRecreateNetworkFails = 0
        recoveryTimer.clear()
        val generation = bumpSessionGeneration("vpn-revoke")
        AppLog.i(
            TAG,
            "VPN permission revoked by Android owner=$owner gen=$generation " +
                "session=${recoverySnapshot.sessionEpoch} transport=${recoverySnapshot.transportEpoch}",
        )
        logTunnelLifecycle(
            "on_revoke",
            JSONObject().put("owner", owner).put("by", "android"),
        )
        if (recoverySnapshot.intent.wantsConnected) {
            dispatchRecovery(
                ConnectionEvent.AttemptFailed(
                    message = "Система отозвала разрешение VPN",
                    keepReady = true,
                ),
            )
        }
        _ui.value = _ui.value.copy(
            state = ConnState.Ready,
            activePath = null,
            statusText = "Система отозвала разрешение VPN",
            lastError = "Система отозвала разрешение VPN",
            connectEnabled = connectAllowed(_ui.value.probe),
            callRecreatePrompt = null,
        )
    }

    private fun postDeferredTunnelStart(lease: DeferredTunnelStart?) {
        if (lease == null) return
        AppLog.v(
            TAG,
            "Deferred tunnel start posted ticket=${lease.ticket} request=${lease.requestId} " +
                "session=${lease.sessionEpoch} gen=${lease.generation} path=${lease.path}",
        )
        logTunnelLifecycle(
            "start_posted",
            JSONObject()
                .put("ticket", lease.ticket)
                .put("request_id", lease.requestId ?: JSONObject.NULL)
                .put("session_epoch", lease.sessionEpoch)
                .put("generation", lease.generation)
                .put("path", lease.path.name)
                .put("gate_epoch", lease.gateEpoch),
            verbose = true,
        )
        mainHandler.post {
            runCatching { startTunnelServiceIfCurrent(lease) }.onFailure { t ->
                AppLog.e(TAG, "Deferred tunnel start failed: ${t.message}")
                if (deferredStartReject(lease) != null) {
                    AppLog.i(
                        TAG,
                        "Deferred start failure ignored: lease no longer current ticket=${lease.ticket}",
                    )
                    logTunnelLifecycle(
                        "start_stale_failure",
                        JSONObject().put("ticket", lease.ticket),
                    )
                    return@post
                }
                val msg = t.message ?: "Не удалось запустить VPN"
                endUserAttempt(msg)
            }
        }
    }

    private fun connectAllowed(probe: ProbeResult?): Boolean {
        if (profile == null) return false
        return when (pathMode) {
            ConnPathMode.Direct, ConnPathMode.Bypass -> true
            ConnPathMode.Auto -> {
                val kind = currentAutoUnderlayKind()
                val underlayUsable =
                    recoverySnapshot.underlay.availability == UnderlayAvailability.Usable
                autoUsesDirectOnWifi(pathMode, kind) ||
                    shouldStartDirectWithoutDiagnostic(pathMode, kind, underlayUsable) ||
                    probe?.preselectedPath != null
            }
        }
    }

    private fun applyFastPathHint(
        result: ProbeResult,
        sessionEpoch: Long,
        networkEpoch: Long,
        capturedNetworkKey: NetworkKey?,
        capturedProfileId: String?,
    ) {
        val now = SystemClock.elapsedRealtime()
        val originKey = capturedNetworkKey ?: result.networkKey
        val incoming = ReachabilityEvidence(
            networkKey = originKey,
            originNetworkKey = originKey,
            profileId = capturedProfileId ?: recoverySnapshot.intent.profileId,
            measuredAtElapsedMs = now,
            yandex = result.yandexOutcome,
            bigtech = result.bigtechOutcome,
            google = result.googleOutcome,
            ruService = result.ruServiceOutcome,
            provision = result.provisionOutcome,
            seriesId = result.seriesId,
        )
        val sample = RestrictionScore.sample(
            cellular = true,
            yandex = result.yandexOutcome,
            bigtech = result.bigtechOutcome,
            google = result.googleOutcome,
            ruService = result.ruServiceOutcome,
        )
        val admit = connectRequests.admitEarly(
            ProbeCallback(
                seriesId = result.seriesId,
                sessionEpoch = sessionEpoch,
                networkEpoch = networkEpoch,
                profileId = incoming.profileId,
                networkKey = originKey,
                ordinarySuccess = result.bigtechOutcome.isSuccess || result.googleOutcome.isSuccess,
                sample = sample,
                wantsConnected = recoverySnapshot.intent.wantsConnected,
                uiState = _ui.value.state,
                liveNetworkKey = recoverySnapshot.underlay.key,
                liveProfileId = recoverySnapshot.intent.profileId,
                userStop = _ui.value.state == ConnState.Disconnecting,
            ),
        )
        if (!admit.accepted) {
            if (connectRequests.lateCallbackBlocked(result.seriesId)) return
            return
        } else if (!admit.applyToReducer) {
            logAutoPathDecision(
                eventKind = "early",
                seriesId = result.seriesId,
                sample = sample,
                evidence = incoming,
                pathBefore = recoverySnapshot.activePath,
                command = "fast-hint",
                roundMs = result.elapsedMs,
            )
            return
        }
        logAutoPathDecision(
            eventKind = "early",
            seriesId = result.seriesId,
            sample = sample,
            evidence = incoming,
            pathBefore = recoverySnapshot.activePath,
            command = "fast-hint",
            roundMs = result.elapsedMs,
        )
        dispatchRecovery(
            ConnectionEvent.ProbeFastHint(
                evidence = incoming,
                sessionEpoch = sessionEpoch,
                networkEpoch = networkEpoch,
            ),
        )
    }

    private fun applyProbe(
        result: ProbeResult,
        sessionEpoch: Long,
        networkEpoch: Long,
        capturedNetworkKey: NetworkKey? = null,
        capturedProfileId: String? = null,
    ) {
        val shown = displayedAutoProbe(pathMode, currentAutoUnderlayKind(), result)
        val capturedKey = result.networkKey ?: capturedNetworkKey
        val profileId = capturedProfileId ?: recoverySnapshot.intent.profileId
        val now = SystemClock.elapsedRealtime()
        val incoming = ReachabilityEvidence(
            networkKey = capturedKey,
            originNetworkKey = capturedKey,
            profileId = profileId,
            measuredAtElapsedMs = now,
            yandex = shown.yandexOutcome,
            bigtech = shown.bigtechOutcome,
            google = shown.googleOutcome,
            ruService = shown.ruServiceOutcome,
            provision = shown.provisionOutcome,
            restriction = shown.restriction,
            whitelistScorePercent = shown.whitelistScorePercent,
            captive = shown.captive,
            ttlUntilElapsedMs = now + RecoverySettings.PROBE_CACHE_TTL_MS,
            bindHandle = shown.bindHandle,
            routeReason = shown.routeReason,
            restrictionReason = shown.restrictionReason,
            seriesId = shown.seriesId,
        )
        val cellular = recoverySnapshot.underlay.kind == UnderlayKind.Cellular
        val sample = RestrictionScore.sample(
            cellular,
            incoming.yandex,
            incoming.bigtech,
            incoming.google,
            incoming.ruService,
        )
        val callback = ProbeCallback(
            seriesId = shown.seriesId,
            sessionEpoch = sessionEpoch,
            networkEpoch = networkEpoch,
            profileId = profileId,
            networkKey = capturedKey,
            ordinarySuccess = shown.bigtechOutcome.isSuccess || shown.googleOutcome.isSuccess,
            sample = sample,
            wantsConnected = recoverySnapshot.intent.wantsConnected,
            uiState = _ui.value.state,
            liveNetworkKey = recoverySnapshot.underlay.key,
            liveProfileId = recoverySnapshot.intent.profileId,
            userStop = _ui.value.state == ConnState.Disconnecting,
        )
        val admit = connectRequests.admitAndApplyFinal(callback) { admitted ->
            val scoreBefore = recoverySnapshot.evidence?.whitelistScorePercent ?: 0
            val pathBefore = recoverySnapshot.activePath
            if (admitted.applyToReducer) {
                dispatchRecovery(
                    ConnectionEvent.ProbeFinished(
                        evidence = incoming,
                        sessionEpoch = sessionEpoch,
                        networkEpoch = networkEpoch,
                    ),
                )
                val folded = recoverySnapshot.evidence
                val overlay = if (folded != null) {
                    shown.withWhitelistEvidence(folded, cellular, now)
                } else {
                    shown
                }
                logAutoPathDecision(
                    eventKind = "final",
                    seriesId = shown.seriesId,
                    sample = sample,
                    evidence = folded,
                    pathBefore = pathBefore,
                    command = recoverySnapshot.recovery.phase.name,
                    roundMs = result.elapsedMs,
                    scoreBefore = scoreBefore,
                )
                _ui.value = _ui.value.copy(
                    probe = overlay,
                    softInfo = softInfoFor(overlay),
                )
                refreshHashFlag()
                return@admitAndApplyFinal
            }
            if (!admitted.idleFold) return@admitAndApplyFinal
            val overlay: ProbeResult
            synchronized(recoveryGate) {
                if (shown.seriesId.isNotEmpty() && shown.seriesId in recoverySnapshot.seenProbeSeriesIds) {
                    return@admitAndApplyFinal
                }
                val folded = foldReachabilityEvidence(
                    previous = recoverySnapshot.evidence,
                    incoming = incoming,
                    cellular = cellular,
                    elapsedMs = now,
                )
                recoverySnapshot = recoverySnapshot.copy(
                    evidence = folded,
                    cellularEvidence = if (cellular) folded else recoverySnapshot.cellularEvidence,
                    seenProbeSeriesIds = rememberProbeSeriesId(
                        recoverySnapshot.seenProbeSeriesIds,
                        shown.seriesId,
                    ),
                )
                overlay = shown.withWhitelistEvidence(folded, cellular, now)
                logAutoPathDecision(
                    eventKind = "final",
                    seriesId = shown.seriesId,
                    sample = sample,
                    evidence = folded,
                    pathBefore = pathBefore,
                    command = "idle-stash",
                    roundMs = result.elapsedMs,
                    scoreBefore = scoreBefore,
                )
            }
            val connecting = _ui.value.state == ConnState.Connecting ||
                _ui.value.state == ConnState.Connected
            val disconnecting = _ui.value.state == ConnState.Disconnecting
            _ui.value = if (connecting || disconnecting || !admitted.allowReadyUi) {
                _ui.value.copy(
                    probe = overlay,
                    softInfo = softInfoFor(overlay),
                )
            } else {
                _ui.value.copy(
                    state = ConnState.Ready,
                    probe = overlay,
                    pathMode = pathMode,
                    statusText = overlay.message,
                    softInfo = softInfoFor(overlay),
                    connectEnabled = connectAllowed(overlay),
                    lastError = if (!connectAllowed(overlay)) overlay.message else null,
                )
            }
            refreshHashFlag()
            Log.i(TAG, "probe class=${overlay.networkClass} path=${overlay.preselectedPath} ${overlay.elapsedMs}ms")
        }
        if (!admit.accepted) {
            AppLog.v(TAG, "probe result rejected series=${shown.seriesId}")
        }
    }

    private fun sessionOwnsProbe(sessionEpoch: Long, networkEpoch: Long): Boolean =
        recoverySnapshot.intent.wantsConnected &&
            !recoverySnapshot.recovery.permit.userStop &&
            recoverySnapshot.recovery.permit.acceptsProbe(
                sessionEpoch,
                networkEpoch,
                recoverySnapshot.recovery.inheritedProbeSessionEpoch,
            )

    private fun logAutoPathDecision(
        eventKind: String,
        seriesId: String,
        sample: RestrictionSample,
        evidence: ReachabilityEvidence?,
        pathBefore: VpnPath?,
        command: String,
        roundMs: Long,
        scoreBefore: Int = 0,
    ) {
        val now = SystemClock.elapsedRealtime()
        val key = evidence?.networkKey ?: recoverySnapshot.underlay.key
        val ttlLeft = evidence?.usableUntilElapsedMs()?.let { until ->
            if (until <= 0L) 0L else (until - now).coerceAtLeast(0L)
        } ?: 0L
        val nextMs = RecoverySettings.nextDiagnosticDelayMs(
            evidence = evidence,
            nowElapsedMs = now,
            key = key,
            profileId = recoverySnapshot.intent.profileId,
        )
        AppLog.i(
            TAG,
            "auto-stage path_decision series=${seriesId.take(8)} event=$eventKind " +
                "net=${key?.handle} sim=${key?.simId} op=${key?.carrier} " +
                "age_ms=${evidence?.let { now - it.observedAtElapsedMs } ?: 0} ttl_ms=$ttlLeft " +
                "sample=$sample yandex=${evidence?.yandex} cf=${evidence?.bigtech} " +
                "google=${evidence?.google} vk=${evidence?.ruService} " +
                "score_before=$scoreBefore score_after=${evidence?.whitelistScorePercent} " +
                "strong_fresh=${evidence?.hasFreshStrong(now, key, recoverySnapshot.intent.profileId)} " +
                "path_before=$pathBefore path_after=${recoverySnapshot.activePath} " +
                "reason=${evidence?.restrictionReason} cmd=$command next_ms=$nextMs round_ms=$roundMs",
        )
    }

    private fun softInfoFor(result: ProbeResult?): String? {
        val parts = mutableListOf<String>()
        val wifiAuto = autoUsesDirectOnWifi(
            pathMode,
            currentAutoUnderlayKind(),
        )
        when (pathMode) {
            ConnPathMode.Direct -> parts += "Режим: только прямое подключение."
            ConnPathMode.Bypass -> parts += "Режим: только обход. Требуется код звонка."
            ConnPathMode.Auto -> Unit
        }
        if (result == null) {
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
        if (isDocumentationHost(directEndpoint) || isDocumentationHost(profile?.bypass?.peer)) {
            parts += "Профиль указывает на документационный IP — импортируйте JSON с вашего VPS."
        }
        when (result.networkClass) {
            NetworkClass.NeedBypass ->
                if (pathMode == ConnPathMode.Auto && !wifiAuto) {
                    parts += if (result.preselectedPath == VpnPath.Bypass) {
                        "Признаки белого списка подтверждены проверками. Подключаемся через обход, без пробного прямого подключения."
                    } else {
                        "Возможны ограничения мобильной сети. Прямое подключение к VPS проверяется."
                    }
                }
            NetworkClass.OpenNeedBypass ->
                if (pathMode == ConnPathMode.Auto && !wifiAuto) {
                    parts += "Сервер управления не ответил. Прямое подключение к VPS проверяется."
                }
            NetworkClass.DataUnconfirmed ->
                parts += "Передача данных не подтверждена. Прямое подключение к VPS проверяется."
            NetworkClass.Captive ->
                parts += "Обнаружена страница авторизации сети. Сначала выполните вход в Wi‑Fi."
            else -> Unit
        }
        val needsHash = pathMode == ConnPathMode.Bypass ||
            (pathMode == ConnPathMode.Auto && !wifiAuto && result.preselectedPath == VpnPath.Bypass)
        if (needsHash && !_ui.value.hasCallHash) {
            parts += "Для обхода сохраните код звонка на устройстве."
        }
        if (_ui.value.hideIp) {
            parts += "Исходящий адрес скрыт."
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun pathLabel(path: VpnPath): String = when (path) {
        VpnPath.Direct -> "прямое"
        VpnPath.Bypass -> "обход"
    }

    private fun hideSuffix(enabled: Boolean = _ui.value.hideIp): String =
        if (enabled) " · адрес скрыт" else ""

    /**
     * Content for the custom VPN shade RemoteViews (qWDTT-style plate).
     */
    data class ShadeContent(
        /** Bold title under system header — path only, brand is in system row. */
        val title: String,
        val rates: String = "",
        val totals: String = "",
        val ip: String = "…",
        /** Short path label on the right of the IP row. */
        val pathLabel: String = "",
        val showTotals: Boolean = true,
        val showWarpIcon: Boolean = false,
        /** БС (apps whitelist) — show red RKN mark beside Cloudflare. */
        val showWhitelistIcon: Boolean = false,
        val statusText: String? = null,
    ) {
        val summary: String get() = listOfNotNull(
            ip.takeIf { it.isNotBlank() },
            pathLabel.takeIf { it.isNotBlank() } ?: title,
        ).joinToString(" · ")
    }

    fun notificationShadeContent(
        sessionStartedAtMs: Long = 0L,
        appsWhitelist: Boolean = false,
    ): ShadeContent {
        val u = _ui.value
        val title = shadeTitle(u.activePath)
        val pathShort = shadePathShort(u.activePath)
        if (u.state == ConnState.PausedTrustedWifi) {
            val t = u.statusText.ifBlank { "Пауза в доверенной сети" }
            return ShadeContent(
                title = "Доверенная Wi‑Fi",
                ip = "—",
                pathLabel = "",
                showTotals = false,
                showWhitelistIcon = appsWhitelist,
                statusText = t,
            )
        }
        if (
            u.state == ConnState.WaitingForNetwork ||
            u.state == ConnState.Recovering ||
            u.state == ConnState.CaptivePortal ||
            u.state == ConnState.NeedsUserAction
        ) {
            return ShadeContent(
                title = title,
                ip = "—",
                pathLabel = pathShort,
                showTotals = false,
                showWhitelistIcon = appsWhitelist,
                statusText = u.uiModel.message.ifBlank { u.statusText },
            )
        }
        if (softRestartInProgress || u.state == ConnState.Connecting) {
            val t = u.statusText.ifBlank { "Переподключение…" }
            val rawIp = EgressIpProbe.current()?.takeIf { it.isNotBlank() }
            return ShadeContent(
                title = title,
                ip = rawIp ?: VPN_EGRESS_CONNECTING_LABEL,
                pathLabel = pathShort,
                showTotals = false,
                showWarpIcon = rawIp != null && (u.hideIp || EgressIpProbe.isLikelyCloudflare(rawIp)),
                showWhitelistIcon = appsWhitelist,
                statusText = t,
            )
        }

        val rates = VpnLiveStats.formatCompactRateLine(VpnLiveStats.downBps, VpnLiveStats.upBps)
        val totals = VpnLiveStats.formatBytesLine(VpnLiveStats.totalRx, VpnLiveStats.totalTx)
        val rawIp = EgressIpProbe.current()?.takeIf { it.isNotBlank() }
        val ip = rawIp ?: VPN_EGRESS_CONNECTING_LABEL
        val cloudflare = rawIp != null && (u.hideIp || EgressIpProbe.isLikelyCloudflare(rawIp))
        return ShadeContent(
            title = title,
            rates = rates,
            totals = totals,
            ip = ip,
            pathLabel = pathShort,
            showWarpIcon = cloudflare,
            showWhitelistIcon = appsWhitelist,
        )
    }

    private fun shadeTitle(path: VpnPath?): String = when (path) {
        VpnPath.Direct -> "Прямое подключение"
        VpnPath.Bypass -> "Обход"
        null -> "ARDTT"
    }

    private fun shadePathShort(path: VpnPath?): String = when (path) {
        VpnPath.Direct -> "Прямое"
        VpnPath.Bypass -> "Обход"
        null -> ""
    }

    /** Fallback single-line text for non-custom / hidden shade. */
    fun notificationContent(
        sessionStartedAtMs: Long = 0L,
        tunIp: String = "—",
    ): Pair<String, String> {
        val c = notificationShadeContent(sessionStartedAtMs)
        return c.rates to c.summary
    }

    fun notificationRunningText(): String = notificationContent().first

    /** Resolve public egress IP after tunnel is up (and after Hide-IP soft-restart). */
    private fun scheduleEgressIpRefresh(reason: String) {
        scope.launch {
            val hideIp = _ui.value.hideIp
            // WARP policy + MSS path need a beat longer than plain VPS SNAT.
            val initialDelay = when {
                reason == "hide-ip-applied" -> 400L
                hideIp -> 2_500L
                else -> 1_000L
            }
            delay(initialDelay)
            val attempts = if (hideIp) 5 else 4
            repeat(attempts) { attempt ->
                var wait = 0
                while (softRestartInProgress && wait < 30) {
                    delay(400)
                    wait++
                }
                if (_ui.value.state != ConnState.Connected) {
                    delay(600)
                    if (_ui.value.state != ConnState.Connected) return@repeat
                }
                AppLog.v(TAG, "egress ip refresh ($reason) attempt=${attempt + 1} hideIp=$hideIp")
                // Prefer underlay to reach provision :9100 while app is excluded from TUN.
                val ip = EgressIpProbe.refresh(
                    hideIp = _ui.value.hideIp,
                    provisionBaseUrl = resolveProvisionUrl(),
                    exitProvisionBaseUrl = resolveExitProvisionUrl(),
                    deviceId = profile?.deviceId,
                    context = appContext,
                    // In Bypass/whitelist scenarios provision can be reachable only via VPN path.
                    viaVpn = shouldProvisionViaVpn(),
                )
                refreshVpnNotification()
                if (!ip.isNullOrBlank()) return@launch
                delay(1_200L * (attempt + 1))
            }
        }
    }

    /** Manual retry from Tunnel status panel. */
    fun requestEgressIpRefresh() {
        if (
            _ui.value.state != ConnState.Connected &&
            _ui.value.state != ConnState.PausedTrustedWifi &&
            _ui.value.state != ConnState.Connecting
        ) {
            return
        }
        EgressIpProbe.invalidate()
        scheduleEgressIpRefresh("manual")
    }

    private fun schedulePresenceHeartbeat() {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (
                _ui.value.state == ConnState.Connected ||
                _ui.value.state == ConnState.PausedTrustedWifi
            ) {
                sendPresenceOnce(force = true)
                delay(60_000L)
            }
        }
    }

    private fun reportPresenceAsync() {
        scope.launch { sendPresenceOnce(force = false) }
    }

    private suspend fun sendPresenceOnce(force: Boolean) {
        val p = profile ?: return
        val base = resolveProvisionUrl()?.takeIf { it.isNotBlank() } ?: return
        val key = "${p.deviceId}|${p.name}|${com.ardtt.app.BuildConfig.VERSION_CODE}"
        val now = System.currentTimeMillis()
        if (!force && key == lastPresenceKey && now - lastPresenceAtMs < PRESENCE_DEBOUNCE_MS) {
            return
        }
        val ext = EgressIpProbe.current().orEmpty()
        val result = com.ardtt.app.deploy.ProvisionAdminApi.reportPresence(
            baseUrl = base,
            deviceId = p.deviceId,
            name = p.name,
            externalIp = ext,
            deviceModel = PhoneModelLabel.current(),
            appVersion = com.ardtt.app.BuildConfig.VERSION_NAME,
            appVersionCode = com.ardtt.app.BuildConfig.VERSION_CODE,
        )
        result.onSuccess {
            lastPresenceKey = key
            lastPresenceAtMs = now
            AppLog.i(
                TAG,
                "presence ok name=${p.name} ver=${com.ardtt.app.BuildConfig.VERSION_NAME}",
            )
        }.onFailure { error ->
            AppLog.w(TAG, "presence failed: ${error.message}")
        }
    }

    private fun handoverSessionAgeMs(): Long {
        if (tunnelStartedAtMs <= 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - tunnelStartedAtMs
    }

    private fun liveDeferredStart(): LiveDeferredStart {
        return LiveDeferredStart(
            wantsConnected = recoverySnapshot.intent.wantsConnected,
            requestId = connectRequests.activeRequestId(),
            sessionEpoch = recoverySnapshot.sessionEpoch,
            generation = sessionGeneration.get(),
        )
    }

    private fun deferredStartReject(lease: DeferredTunnelStart): String? {
        return deferredStartRejectReason(
            lease = lease,
            live = liveDeferredStart(),
            serializerGateEpoch = tunnelStartSerializer.gateEpoch,
        )
    }

    private fun startTunnel(path: VpnPath) {
        val lease = tunnelStartSerializer.nextLease(
            requestId = connectRequests.activeRequestId(),
            sessionEpoch = recoverySnapshot.sessionEpoch,
            generation = sessionGeneration.get(),
            path = path,
        )
        if (!tunnelStartSerializer.admitStart(lease)) {
            AppLog.v(
                TAG,
                "Defer tunnel start ticket=${lease.ticket} path=$path request=${lease.requestId} " +
                    "session=${lease.sessionEpoch} gen=${lease.generation}",
            )
            logTunnelLifecycle(
                "start_queued",
                JSONObject()
                    .put("ticket", lease.ticket)
                    .put("path", path.name)
                    .put("request_id", lease.requestId ?: JSONObject.NULL)
                    .put("session_epoch", lease.sessionEpoch)
                    .put("generation", lease.generation)
                    .put("gate_epoch", lease.gateEpoch),
                verbose = true,
            )
            return
        }
        startTunnelServiceIfCurrent(lease)
    }

    private fun startTunnelServiceIfCurrent(lease: DeferredTunnelStart) {
        val reject = deferredStartReject(lease)
        if (reject != null) {
            AppLog.i(
                TAG,
                "Rejected deferred tunnel start ticket=${lease.ticket} reason=$reject " +
                    "request=${lease.requestId} session=${lease.sessionEpoch} gen=${lease.generation}",
            )
            logTunnelLifecycle(
                "start_rejected",
                JSONObject()
                    .put("ticket", lease.ticket)
                    .put("reason", reject)
                    .put("request_id", lease.requestId ?: JSONObject.NULL)
                    .put("session_epoch", lease.sessionEpoch)
                    .put("generation", lease.generation)
                    .put("path", lease.path.name),
            )
            return
        }
        startTunnelService(lease.path)
        logTunnelLifecycle(
            "start_executed",
            JSONObject()
                .put("ticket", lease.ticket)
                .put("request_id", lease.requestId ?: JSONObject.NULL)
                .put("session_epoch", lease.sessionEpoch)
                .put("generation", lease.generation)
                .put("path", lease.path.name),
        )
    }

    private fun startTunnelService(path: VpnPath) {
        tunnelStartedAtMs = System.currentTimeMillis()
        val addr = when (path) {
            VpnPath.Direct -> profile?.direct?.address ?: tunAddress
            VpnPath.Bypass -> profile?.bypass?.address ?: tunAddress
        } ?: "10.8.0.2"

        TunnelSessionHolder.config = TunnelSessionConfig(
            path = path,
            profile = profile,
            tunAddress = addr,
            hideIp = _ui.value.hideIp,
            callHash = callHashOrNull(),
            workers = workers,
            silentRecreate = silentRecreate,
            dialPathName = dialPath.name,
            callEpoch = recoverySnapshot.call.callEpoch,
        )

        val transportOwner = connectRequests.bindNewTransport()
        val intent = Intent(appContext, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_START
            putExtra(VpnTunnelService.EXTRA_PATH, path.name)
            putExtra(VpnTunnelService.EXTRA_HIDE_IP, false)
            putExtra(VpnTunnelService.EXTRA_TUN_ADDRESS, addr)
            putExtra(VpnTunnelService.EXTRA_TRANSPORT_OWNER, transportOwner)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            tunnelStartSerializer.noteStartIssued(transportOwner)
            AppLog.i(
                TAG,
                "Tunnel START issued owner=$transportOwner path=$path " +
                    "request=${connectRequests.activeRequestId()} " +
                    "session=${recoverySnapshot.sessionEpoch} gen=${sessionGeneration.get()}",
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "startForegroundService failed: ${t.message}")
            throw t
        }
    }

    private fun stopTunnel() {
        presenceJob?.cancel()
        presenceJob = null
        val owner = tunnelStartSerializer.beginStop()
        AppLog.i(
            TAG,
            "Tunnel STOP issued by=manager owner=$owner gen=${sessionGeneration.get()} " +
                "session=${recoverySnapshot.sessionEpoch} transport=${recoverySnapshot.transportEpoch} " +
                "wants=${recoverySnapshot.intent.wantsConnected}",
        )
        logTunnelLifecycle(
            "stop_issued",
            JSONObject()
                .put("by", "manager")
                .put("owner", owner),
        )
        EgressIpProbe.clear()
        TunnelSessionHolder.config = null
        val intent = Intent(appContext, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_STOP
            if (owner != 0L) {
                putExtra(VpnTunnelService.EXTRA_TRANSPORT_OWNER, owner)
            }
        }
        appContext.startService(intent)
    }

    private suspend fun waitForBypassPathConfirm(
        generation: Long,
        sessionEpoch: Long,
        transportEpoch: Long,
        networkKey: NetworkKey?,
        callEpoch: Long,
        onPartial: suspend (PathConfirmVerdict) -> Unit = {},
    ): PathConfirmVerdict {
        val deadline = SystemClock.elapsedRealtime() + BYPASS_WORKERS_WAIT_MS
        var baseline = TransportHealth.snapshot()
        var capturedTunGen = baseline.tunGen
        var baselineOk = baseline.tunWriteOk
        var baselineErr = baseline.tunWriteErr
        var baselineDown = baseline.exactDownBytes
        var last = PathConfirmVerdict.NotReady
        while (SystemClock.elapsedRealtime() < deadline) {
            if (generation != sessionGeneration.get()) return PathConfirmVerdict.Stale
            val snap = TransportHealth.snapshot()
            // Adopt the first known TUN generation of this operation as the baseline.
            if (capturedTunGen < 0L && snap.tunGen >= 0L &&
                snap.processId == baseline.processId &&
                snap.operationId == baseline.operationId
            ) {
                capturedTunGen = snap.tunGen
                baselineOk = snap.tunWriteOk
                baselineErr = snap.tunWriteErr
                baselineDown = snap.exactDownBytes
                baseline = snap
                AppLog.i(
                    TAG,
                    "auto-stage bypass_baseline_adopted tunGen=$capturedTunGen " +
                        "op=${snap.operationId} process=${snap.processId}",
                )
            }
            val verdict = withContext(Dispatchers.IO) {
                PathConfirm.verdict(
                    PathConfirm.assembleBypass(
                        capturedSessionEpoch = sessionEpoch,
                        capturedTransportEpoch = transportEpoch,
                        capturedNetworkKey = networkKey,
                        eventSessionEpoch = recoverySnapshot.sessionEpoch,
                        eventTransportEpoch = recoverySnapshot.transportEpoch,
                        eventNetworkKey = recoverySnapshot.underlay.key,
                        capturedCallEpoch = callEpoch,
                        eventCallEpoch = recoverySnapshot.call.callEpoch,
                        capturedTunGen = capturedTunGen,
                        eventTunGen = snap.tunGen,
                        tunWriteOkDelta = (snap.tunWriteOk - baselineOk).coerceAtLeast(0L),
                        tunWriteErrDelta = (snap.tunWriteErr - baselineErr).coerceAtLeast(0L),
                        usefulRxDelta = (snap.exactDownBytes - baselineDown).coerceAtLeast(0L),
                        workersPresent = snap.activeWorkers > 0,
                        capturedProcessId = baseline.processId,
                        eventProcessId = snap.processId,
                        capturedOperationId = baseline.operationId,
                        eventOperationId = snap.operationId,
                    ),
                )
            }
            last = verdict
            if (verdict == PathConfirmVerdict.PathConfirmed) {
                AppLog.i(TAG, "auto-stage path_confirmed path=bypass gen=$generation")
                return verdict
            }
            if (verdict == PathConfirmVerdict.Stale || verdict == PathConfirmVerdict.WriteFailed) {
                return verdict
            }
            if (verdict == PathConfirmVerdict.BackendRunning ||
                verdict == PathConfirmVerdict.ProtocolReady
            ) {
                onPartial(verdict)
            }
            delay(BYPASS_WORKERS_POLL_MS)
        }
        AppLog.i(TAG, "auto-stage path_confirm_timeout path=bypass gen=$generation verdict=$last")
        return last
    }

    private suspend fun waitForDirectPathConfirm(
        generation: Long,
        sessionEpoch: Long,
        transportEpoch: Long,
        networkKey: NetworkKey?,
        callEpoch: Long,
        onPartial: suspend (PathConfirmVerdict) -> Unit = {},
    ): PathConfirmVerdict {
        val deadline = SystemClock.elapsedRealtime() + RecoverySettings.DIRECT_LIMITED_TRY_MS
        // Prefer the pre-awgTurnOn operation baseline so early handshake/RX are not lost.
        val opBaseline = VpnLiveStats.currentDirectOperation()
        val capturedOpId = opBaseline?.operationId ?: -1L
        val handshakeBaseline = opBaseline?.handshakeSecAtStart ?: 0L
        val rxBaseline = opBaseline?.rxAtStart ?: 0L
        val capturedHandle = (opBaseline?.handleAtStart?.takeIf { it >= 0 }
            ?: VpnLiveStats.currentAwgHandle()).toLong()
        var last = PathConfirmVerdict.NotReady
        while (SystemClock.elapsedRealtime() < deadline) {
            if (generation != sessionGeneration.get()) return PathConfirmVerdict.Stale
            val verdict = withContext(Dispatchers.IO) {
                val sample = VpnLiveStats.readDirectAwgSample()
                val liveOp = VpnLiveStats.currentDirectOperation()
                val source = if (sample != null) {
                    PathConfirmSource.DirectAwg
                } else {
                    PathConfirmSource.Unknown
                }
                PathConfirm.verdict(
                    PathConfirm.assembleDirect(
                        capturedSessionEpoch = sessionEpoch,
                        capturedTransportEpoch = transportEpoch,
                        capturedNetworkKey = networkKey,
                        eventSessionEpoch = recoverySnapshot.sessionEpoch,
                        eventTransportEpoch = recoverySnapshot.transportEpoch,
                        eventNetworkKey = recoverySnapshot.underlay.key,
                        capturedCallEpoch = callEpoch,
                        eventCallEpoch = recoverySnapshot.call.callEpoch,
                        capturedHandle = capturedHandle,
                        eventHandle = (sample?.handle ?: -1).toLong(),
                        handshakeBaselineSec = handshakeBaseline,
                        handshakeNowSec = sample?.handshakeSec ?: 0L,
                        rxBaseline = rxBaseline,
                        rxNow = sample?.rx ?: 0L,
                        source = source,
                        capturedOperationId = capturedOpId,
                        eventOperationId = liveOp?.operationId ?: -1L,
                        requireCallEpoch = false,
                    ),
                )
            }
            last = verdict
            if (verdict == PathConfirmVerdict.PathConfirmed) {
                AppLog.i(TAG, "auto-stage path_confirmed path=direct gen=$generation")
                return verdict
            }
            if (verdict == PathConfirmVerdict.Stale) return verdict
            if (verdict == PathConfirmVerdict.ProtocolReady) {
                onPartial(verdict)
            }
            delay(250L)
        }
        if (last == PathConfirmVerdict.ProtocolReady) {
            AppLog.i(TAG, "auto-stage protocol_ready path=direct gen=$generation")
        } else {
            AppLog.i(TAG, "auto-stage path_confirm_timeout path=direct gen=$generation verdict=$last")
        }
        return last
    }

    private fun requestDiscardCallSession(
        staleCallEpoch: Long,
        stopActive: Boolean,
        reason: String,
    ) {
        val intent = Intent(appContext, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_SESSION_CONTROL)
            .putExtra(VpnTunnelService.EXTRA_DISCARD_PARKED, true)
            .putExtra(VpnTunnelService.EXTRA_DISCARD_ACTIVE, stopActive)
            .putExtra(VpnTunnelService.EXTRA_CALL_EPOCH, staleCallEpoch)
        AppLog.i(TAG, "discard call session epoch=$staleCallEpoch active=$stopActive ($reason)")
        runCatching { appContext.startService(intent) }
    }

    private fun requestDiscardParkedCall(reason: String) {
        requestDiscardCallSession(
            staleCallEpoch = recoverySnapshot.call.callEpoch,
            stopActive = false,
            reason = reason,
        )
    }

    fun onWatchdogFault(path: VpnPath, reason: String) {
        AppLog.w(TAG, "watchdog fact: $reason")
        dispatchRecovery(
            ConnectionEvent.TransportDied(
                sessionEpoch = recoverySnapshot.sessionEpoch,
                transportEpoch = recoverySnapshot.transportEpoch,
                path = path,
            ),
        )
    }

    private fun screenInteractive(): Boolean {
        val pm = appContext.getSystemService(android.os.PowerManager::class.java)
        return pm?.isInteractive == true
    }

    private fun logTunnelLifecycle(
        action: String,
        extra: JSONObject = JSONObject(),
        verbose: Boolean = false,
    ) {
        extra.put("ui", _ui.value.state.name)
        extra.put("intent_wants", recoverySnapshot.intent.wantsConnected)
        extra.put("recovery_phase", recoverySnapshot.recovery.phase.name)
        extra.put("transport", recoverySnapshot.transport.name)
        if (!extra.has("active_path")) {
            extra.put("active_path", _ui.value.activePath?.name ?: JSONObject.NULL)
        }
        if (!extra.has("request_id")) {
            extra.put("request_id", connectRequests.activeRequestId() ?: JSONObject.NULL)
        }
        if (!extra.has("owner")) {
            extra.put("owner", tunnelStartSerializer.lastBoundOwner)
        }
        if (!extra.has("session_epoch")) {
            extra.put("session_epoch", recoverySnapshot.sessionEpoch)
        }
        extra.put("network_epoch", recoverySnapshot.networkEpoch)
        if (!extra.has("transport_epoch")) {
            extra.put("transport_epoch", recoverySnapshot.transportEpoch)
        }
        if (!extra.has("call_epoch")) {
            extra.put("call_epoch", recoverySnapshot.call.callEpoch)
        }
        if (!extra.has("generation")) {
            extra.put("generation", sessionGeneration.get())
        }
        extra.put("underlay", recoverySnapshot.underlay.kind.name)
        extra.put("screen_on", screenInteractive())
        extra.put("trusted_wifi", _ui.value.state == ConnState.PausedTrustedWifi)
        extra.put("revoke", vpnPermissionRevoked)
        val summary = buildString {
            append(action)
            extra.optString("by").takeIf { it.isNotBlank() }?.let { append(" by=").append(it) }
            extra.optString("cause").takeIf { it.isNotBlank() }?.let { append(" cause=").append(it) }
            extra.optString("reason").takeIf { it.isNotBlank() }?.let { append(" reason=").append(it) }
            extra.optString("decision").takeIf { it.isNotBlank() }?.let { append(" decision=").append(it) }
            extra.optString("kind").takeIf { it.isNotBlank() }?.let { append(" kind=").append(it) }
        }
        if (verbose) {
            AppLog.v(TAG, "lifecycle $summary")
        } else {
            AppLog.i(TAG, "lifecycle $summary")
        }
        TelemetryBridge.lifecycle(action, extra)
    }

    companion object {
        private const val TAG = "ConnMgr"
        private const val DEFAULT_WORKERS = BypassWorkers.DEFAULT
        private const val BYPASS_WORKERS_WAIT_MS = 25_000L
        private const val BYPASS_WORKERS_POLL_MS = 250L
        private const val RETRY_WAKELOCK_TAG = "ardtt:recovery-retry"
        private const val TRANSPORT_RESTART_DEBOUNCE_MS = 150L
        private const val PRESENCE_DEBOUNCE_MS = 5_000L

        @Volatile
        private var instance: ConnectionManager? = null

        fun get(context: Context): ConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
        }

        fun getOrNull(): ConnectionManager? = instance

        /** RFC 5737 documentation / demo hosts — not routable. */
        fun isDocumentationHost(endpoint: String?): Boolean {
            val host = endpoint?.substringBefore(':')?.trim().orEmpty()
            if (host.isEmpty()) return false
            return host.startsWith("203.0.113.") ||
                host.startsWith("198.51.100.") ||
                host.startsWith("192.0.2.") ||
                host.equals("example.com", ignoreCase = true)
        }
    }
}

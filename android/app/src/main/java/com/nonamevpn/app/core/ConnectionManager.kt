package com.nonamevpn.app.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.nonamevpn.app.bypass.CallHashStore
import com.nonamevpn.app.bypass.CallRecreatePrompt
import com.nonamevpn.app.bypass.DeadCallAction
import com.nonamevpn.app.bypass.DialPath
import com.nonamevpn.app.bypass.VkCallHashGenerator
import com.nonamevpn.app.bypass.VkLoginActivity
import com.nonamevpn.app.bypass.VkSession
import com.nonamevpn.app.bypass.decideDeadCallAction
import com.nonamevpn.app.bypass.isDeadCallMessage
import com.nonamevpn.app.profile.VpnProfile
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.unlock.DeviceUnlockCopy
import com.nonamevpn.app.tunnel.TunnelSessionConfig
import com.nonamevpn.app.tunnel.TunnelSessionHolder
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

enum class ConnState {
    Idle,
    Probing,
    Ready,
    Connecting,
    Connected,
    /** VPN paused on trusted Wi‑Fi; service may still be foreground waiting to resume. */
    PausedTrustedWifi,
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
)

class ConnectionManager(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(ConnUiState())
    val ui: StateFlow<ConnUiState> = _ui.asStateFlow()
    private val hashStore = CallHashStore(appContext)
    private val settingsRepo = AppSettingsRepository(appContext)
    @Volatile private var alphaUnlockedCached: Boolean? = null

    private var probeJob: Job? = null
    private var connectJob: Job? = null
    private var presenceJob: Job? = null
    private var profile: VpnProfile? = null
    private var directEndpoint: String? = null
    private var provisionUrl: String? = null
    private var tunAddress: String? = null
    private var workers: Int = BypassWorkers.DEFAULT
    @Volatile private var silentRecreate: Boolean = false
    @Volatile private var callRecreateAttempts: Int = 0
    private var callRecreateJob: Job? = null
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
     * Bypass→Direct on the same network: TCP :9100 can still look DirectOk.
     */
    private var deadDirectBindHandle: Long? = null
    private var blockBypassToDirectUntilUnderlayChange: Boolean = false

    init {
        scope.launch {
            settingsRepo.alphaUnlockedFlow.collect { alphaUnlockedCached = it }
        }
    }

    private fun rejectLockedConnect() {
        AppLog.w(TAG, "Connect ignored — device not confirmed")
        Toast.makeText(
            appContext,
            DeviceUnlockCopy.CONNECT_BLOCKED,
            Toast.LENGTH_LONG,
        ).show()
    }

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
    private fun shouldProvisionViaVpn(probe: ProbeResult? = _ui.value.probe): Boolean {
        val path = _ui.value.activePath ?: TunnelSessionHolder.config?.path
        if (path == VpnPath.Bypass) return true
        val p = probe ?: return false
        return p.preselectedPath == VpnPath.Bypass ||
            p.networkClass == NetworkClass.NeedBypass ||
            p.networkClass == NetworkClass.OpenNeedBypass
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
                // Egress flip on VPS invalidates in-flight TCP (new SNAT IP).
                // Server conntrack flush alone leaves phone sockets waiting ~RTO (30s).
                // Soft-restart TUN so apps reconnect in ~1–2s instead of hanging.
                if (_ui.value.state == ConnState.Connected) {
                    delay(1_500) // nvpn-warp debounce + apply ip rules
                    if (_ui.value.hideIp != enabled) return@launch
                    val why = if (enabled) {
                        "Hide-IP: egress → WARP"
                    } else {
                        "Hide-IP: egress → VPS"
                    }
                    AppLog.v(TAG, "Hide-IP applied — $why (soft-restart sockets)")
                    requestTransportRestart(why)
                    // Soft-restart also refreshes on tunnel-up; schedule a late
                    // WARP/VPS IP fetch in case the first attempt races policy apply.
                    scheduleEgressIpRefresh("hide-ip-applied")
                } else {
                    scheduleEgressIpRefresh("hide-ip-synced-offline")
                }
            } else {
                AppLog.e(TAG, "hide-ip sync failed: ${r.exceptionOrNull()?.message}")
                if (viaVpn) {
                    _ui.value = _ui.value.copy(
                        lastError = "Скрытие адреса не синхронизировано: ${r.exceptionOrNull()?.message}",
                    )
                } else {
                    pendingHideIpSync = enabled
                    AppLog.i(TAG, "Hide-IP will retry after tunnel up")
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

    /** Soft-restart transport if a session is up (exclusions / network / manual). */
    fun requestTransportRestart(reason: String, pathOverride: VpnPath? = null) {
        val state = _ui.value.state
        if (
            state != ConnState.Connected &&
            state != ConnState.PausedTrustedWifi &&
            state != ConnState.Connecting
        ) {
            return
        }
        transportRestartJob?.cancel()
        transportRestartJob = scope.launch {
            delay(TRANSPORT_RESTART_DEBOUNCE_MS)
            val live = _ui.value.state
            if (
                live != ConnState.Connected &&
                live != ConnState.PausedTrustedWifi &&
                live != ConnState.Connecting
            ) {
                return@launch
            }
            val intent = Intent(appContext, VpnTunnelService::class.java)
                .setAction(VpnTunnelService.ACTION_RESTART_TRANSPORT)
                .putExtra(VpnTunnelService.EXTRA_RESTART_REASON, reason)
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
        this.workers = DEFAULT_WORKERS
    }

    fun setSilentRecreate(enabled: Boolean) {
        silentRecreate = enabled
    }

    fun setDialPath(path: DialPath) {
        dialPath = path
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
        if (switchLive) {
            maybeSwitchLivePath(mode)
        }
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
        val cleaned = com.nonamevpn.app.bypass.VkUrl.strip(hash)
        if (!com.nonamevpn.app.bypass.VkUrl.isPlausibleHash(cleaned)) return
        hashStore.setHash(profile?.name, cleaned)
        refreshHashFlag()
    }

    fun clearCallHash() {
        hashStore.clear()
        refreshHashFlag()
    }

    fun callHashOrNull(): String? = hashStore.getHash(profile?.name)

    private fun refreshHashFlag() {
        _ui.value = _ui.value.copy(
            hasCallHash = hashStore.hasHash(profile?.name),
        )
        com.nonamevpn.app.QuickToggleTileService.requestTileUpdate(appContext)
    }

    fun startInitialProbe() {
        val busy =
            _ui.value.state == ConnState.Connecting ||
                _ui.value.state == ConnState.Connected ||
                _ui.value.state == ConnState.PausedTrustedWifi ||
                _ui.value.state == ConnState.Disconnecting
        if (busy) {
            AppLog.v(TAG, "Probe skipped — tunnel busy (${_ui.value.state})")
            return
        }
        if (_ui.value.callRecreatePrompt != null) {
            AppLog.v(TAG, "Probe skipped — waiting for call recreate")
            return
        }
        probeJob?.cancel()
        probeJob = scope.launch {
            AppLog.v(TAG, "Probe start endpoint=$directEndpoint provision=$provisionUrl")
            _ui.value = _ui.value.copy(
                state = ConnState.Probing,
                statusText = "Определение сети…",
                softInfo = null,
                connectEnabled = false,
                lastError = null,
            )
            val result = NetworkProbe.probe(
                appContext,
                provisionUrl,
                directEndpoint = directEndpoint,
                quick = true,
            )
            AppLog.v(
                TAG,
                "Probe done path=${result.preselectedPath} class=${result.networkClass} " +
                    "yandex=${result.yandexOk} vps=${result.provisionOk} ${result.elapsedMs}ms",
            )
            // Don't clobber an in-flight Connect started while we probed.
            if (_ui.value.state == ConnState.Connecting || _ui.value.state == ConnState.Connected) {
                AppLog.w(TAG, "Probe result ignored — already connecting/connected")
                return@launch
            }
            applyProbe(result)
        }
    }

    fun connect() {
        if (alphaUnlockedCached == false) {
            rejectLockedConnect()
            return
        }
        val current = _ui.value
        val mode = pathMode
        val probePreferred = current.probe?.preselectedPath
        if (current.state == ConnState.Probing || current.state == ConnState.Connecting) {
            AppLog.w(TAG, "Connect ignored (state=${current.state} mode=$mode)")
            return
        }
        if (current.state == ConnState.Connected) return
        if (current.state == ConnState.PausedTrustedWifi) {
            AppLog.i(TAG, "Connect ignored — paused on trusted Wi‑Fi (leave network or disable feature)")
            return
        }
        if (mode == ConnPathMode.Auto && probePreferred == null) {
            val kind = underlayKindOf(pickBestUnderlayNetwork(appContext))
            val bypassAllowed = callHashOrNull() != null
            if (!shouldSkipConnectProbe(mode, bypassAllowed, kind)) {
                AppLog.w(TAG, "Connect ignored (auto, no probe path)")
                return
            }
        }
        if (profile == null) {
            AppLog.w(TAG, "Connect ignored — no profile")
            return
        }
        handoverProbeStreak = ProbeStreak()
        lastHandoverBindHandle = null
        deadDirectBindHandle = null
        blockBypassToDirectUntilUnderlayChange = false
        callRecreateAttempts = 0

        // Sync Hide-IP preference to VPS (policy route via warp0). WARP must be up.
        if (current.hideIp) {
            AppLog.v(TAG, "Hide-IP on — asking provision to route host via WARP")
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            if (!settingsRepo.alphaUnlockedSnapshot()) {
                rejectLockedConnect()
                return@launch
            }
            try {
                val snap = _ui.value
                val lastGood = snap.probe
                val liveMode = pathMode
                val kind = underlayKindOf(pickBestUnderlayNetwork(appContext))
                val bypassAllowed = callHashOrNull() != null
                val skipProbe = shouldSkipConnectProbe(liveMode, bypassAllowed, kind)
                val labelPreferred = when {
                    liveMode == ConnPathMode.Direct -> VpnPath.Direct
                    liveMode == ConnPathMode.Bypass || skipProbe -> VpnPath.Bypass
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
                        _ui.value = _ui.value.copy(
                            state = ConnState.Error,
                            lastError = "Не удалось скрыть адрес: ${r.exceptionOrNull()?.message}",
                            connectEnabled = true,
                        )
                        return@launch
                    }
                    lastHideIpSent = true
                } else if (deferHideIp) {
                    pendingHideIpSync = true
                    AppLog.v(TAG, "Hide-IP deferred until Bypass tunnel (underlay cannot reach provision)")
                } else if (lastHideIpSent == true) {
                    runCatching { syncHideIpToProvision(false, viaVpn = false) }
                        .onSuccess { lastHideIpSent = false }
                }
                val selectedApps = runCatching { settingsRepo.excludedAppsSnapshot() }
                    .getOrDefault(emptySet())
                val whitelistOn = runCatching { settingsRepo.appsWhitelistModeSnapshot() }
                    .getOrDefault(false)
                val fresh: ProbeResult
                val usePath: VpnPath?
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
                } else {
                    fresh = NetworkProbe.probe(
                        appContext,
                        provisionUrl,
                        directEndpoint = directEndpoint,
                        quick = true,
                    )
                    usePath = resolveConnectPath(
                        pathMode,
                        probePreferred,
                        lastGood,
                        fresh,
                        underlayKind = kind,
                        bypassAllowed = bypassAllowed,
                    )
                    AppLog.v(
                        TAG,
                        "Connect re-probe path=${fresh.preselectedPath} → use=$usePath " +
                            "mode=$pathMode yandex=${fresh.yandexOk} vps=${fresh.provisionOk} " +
                            "kind=$kind " +
                            "whitelist=$whitelistOn apps=${selectedApps.size} " +
                            SplitTunnel.logSample(selectedApps),
                    )
                }
                if (usePath == null) {
                    applyProbe(fresh)
                    _ui.value = _ui.value.copy(
                        state = ConnState.Error,
                        lastError = fresh.message ?: "Нет доступного пути",
                        connectEnabled = false,
                    )
                    AppLog.e(TAG, "Connect aborted: ${fresh.message}")
                    return@launch
                }
                if (isDocumentationHost(directEndpoint) || isDocumentationHost(profile?.bypass?.peer)) {
                    AppLog.e(TAG, "Profile uses documentation IP — import JSON from VPS")
                    _ui.value = _ui.value.copy(
                        state = ConnState.Error,
                        probe = fresh,
                        lastError = "Профиль с документационным IP (203.0.113.x). Импортируйте JSON с VPS.",
                        connectEnabled = true,
                        softInfo = softInfoFor(fresh),
                    )
                    return@launch
                }
                if (usePath == VpnPath.Direct) {
                    val d = profile?.direct
                    if (d == null || d.privateKey.isBlank() || d.peerPublicKey.isBlank()) {
                        AppLog.e(TAG, "Direct: missing AWG keys in profile")
                        _ui.value = _ui.value.copy(
                            state = ConnState.Error,
                            lastError = "В профиле нет ключей AWG — нужен JSON с provision/smoke",
                            connectEnabled = true,
                        )
                        return@launch
                    }
                }
                if (usePath == VpnPath.Bypass && callHashOrNull().isNullOrBlank()) {
                    AppLog.e(TAG, "Bypass: call hash missing")
                    _ui.value = _ui.value.copy(
                        state = ConnState.Error,
                        probe = fresh,
                        lastError = "Для обхода необходимо сохранить код звонка на устройстве.",
                        connectEnabled = true,
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
                _ui.value = _ui.value.copy(
                    state = ConnState.Error,
                    lastError = t.message ?: "Сбой Connect",
                    connectEnabled = true,
                )
            }
        }
    }

    fun reportUserError(message: String) {
        AppLog.w(TAG, message)
        _ui.value = _ui.value.copy(
            state = ConnState.Error,
            lastError = message,
            statusText = "Требуется действие",
            connectEnabled = connectAllowed(_ui.value.probe),
        )
    }

    fun disconnect() {
        val state = _ui.value.state
        if (state == ConnState.Disconnecting) {
            AppLog.v(TAG, "Disconnect ignored: already disconnecting")
            return
        }
        if (state == ConnState.Probing) {
            probeJob?.cancel()
            probeJob = null
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
            state != ConnState.Connected &&
            state != ConnState.Connecting &&
            state != ConnState.PausedTrustedWifi
        ) {
            return
        }
        softRestartInProgress = false
        handoverProbeStreak = ProbeStreak()
        lastHandoverBindHandle = null
        deadDirectBindHandle = null
        blockBypassToDirectUntilUnderlayChange = false
        callRecreateAttempts = 0
        callRecreateJob?.cancel()
        callRecreateJob = null
        presenceJob?.cancel()
        presenceJob = null
        connectJob?.cancel()
        connectJob = null
        transportRestartJob?.cancel()
        transportRestartJob = null
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
     * VPS reachable → Direct even on БС. Otherwise 77.88.8.8 → Bypass.
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
                "health=${fresh.provisionOk} ${fresh.elapsedMs}ms",
        )

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
            pathMode = mode,
            currentPath = currentPath,
            probedPath = fresh.preselectedPath,
            bypassAllowed = bypassAllowed,
            sessionAgeMs = handoverSessionAgeMs(),
            currentPathHealthy = pathHealthy,
            underlayVpsReachable = vpsReachable,
            sameProbeStreak = handoverProbeStreak.count,
            underlayChanged = underlayChanged,
            allowBypassToDirect = allowBypassToDirect,
            directFailedOnCurrentUnderlay = directFailedOnCurrentUnderlay,
            underlayKind = kind,
        )
        when (decision) {
            NetworkHandoverDecision.NoAction -> {
                AppLog.v(
                    TAG,
                    "Handover: no action path=$currentPath probe=${fresh.preselectedPath} " +
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
                    softInfo = fresh.message,
                    lastError = null,
                    connectEnabled = true,
                )
            }
            NetworkHandoverDecision.SoftRestartSamePath -> {
                if (
                    fresh.preselectedPath == VpnPath.Bypass &&
                    currentPath == VpnPath.Direct &&
                    !bypassAllowed
                ) {
                    AppLog.w(TAG, "Handover: need Bypass but no call hash — keep Direct soft-restart")
                } else {
                    AppLog.v(TAG, "Handover: keep $currentPath (probe=${fresh.preselectedPath})")
                }
            }
            NetworkHandoverDecision.HoldWaitForNetwork -> {
                AppLog.v(TAG, "Handover: hold — нет устойчивой сети, ждём underlay")
                _ui.value = _ui.value.copy(
                    statusText = "Ожидание сети…",
                    softInfo = fresh.message.ifBlank {
                        "Смена SIM/Wi‑Fi — ждём рабочий underlay."
                    },
                    lastError = null,
                    connectEnabled = true,
                )
            }
        }
        return decision
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
        )
    }

    private fun currentPathLooksHealthy(path: VpnPath): Boolean = when (path) {
        VpnPath.Bypass -> TransportHealth.activeWorkers > 0
        VpnPath.Direct -> VpnLiveStats.totalRx > 0L || VpnLiveStats.downBps > 0L
    }

    /**
     * Direct is Connected but TUN has no inbound bytes. Auto+hash switches to
     * Bypass; forced Direct stops so the phone is not a blackhole.
     */
    fun onDeadDirectNoRx() {
        val current = TunnelSessionHolder.config?.path ?: _ui.value.activePath
        if (current != VpnPath.Direct) return
        when (
            decideDeadDirectAction(
                pathMode = pathMode,
                bypassAllowed = callHashOrNull() != null,
            )
        ) {
            DeadDirectDecision.KeepWatching -> Unit
            DeadDirectDecision.SwitchToBypass -> {
                AppLog.w(TAG, "Dead Direct (no TUN rx) — Auto → Bypass")
                handoverProbeStreak = ProbeStreak(VpnPath.Bypass, 1)
                deadDirectBindHandle = lastHandoverBindHandle
                blockBypassToDirectUntilUnderlayChange = true
                applySessionPath(VpnPath.Bypass)
                _ui.value = _ui.value.copy(
                    state = ConnState.Connecting,
                    activePath = VpnPath.Bypass,
                    statusText = "Прямое без выхода в сеть. Выполняется переход на обход…",
                    lastError = null,
                    connectEnabled = true,
                )
                requestTransportRestart(
                    reason = "Прямое без выхода в сеть — переход на обход",
                    pathOverride = VpnPath.Bypass,
                )
            }
            DeadDirectDecision.FailSession -> {
                AppLog.w(TAG, "Dead Direct (no TUN rx) — stop VPN to avoid blackhole")
                onTunnelFailed(
                    "Прямое подключение без выхода в сеть. Туннель остановлен, чтобы телефон не остался без интернета.",
                )
            }
        }
    }

    fun onUnderlyingNetworkLost() {
        if (_ui.value.state != ConnState.Connected && _ui.value.state != ConnState.Connecting) return
        scope.launch {
            _ui.value = _ui.value.copy(
                statusText = "Ожидание сети…",
                softInfo = "Подключение будет восстановлено при появлении сети.",
            )
        }
    }

    fun onTrustedWifiWaiting(ssid: String) {
        softRestartInProgress = false
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
        scope.launch {
            softRestartInProgress = false
            if (path == VpnPath.Bypass) {
                _ui.value = _ui.value.copy(
                    state = ConnState.Connecting,
                    activePath = path,
                    statusText = "Ожидание каналов обхода…",
                    connectEnabled = false,
                    lastError = null,
                    softInfo = softInfoFor(_ui.value.probe),
                )
                if (!waitForBypassWorkers()) {
                    AppLog.e(TAG, "Bypass: no active TURN workers after ${BYPASS_WORKERS_WAIT_MS}ms")
                    onTunnelFailed("Обход недоступен: нет активных каналов. Проверьте код звонка и сеть.")
                    return@launch
                }
            }
            _ui.value = _ui.value.copy(
                state = ConnState.Connected,
                activePath = path,
                statusText = when (path) {
                    VpnPath.Direct -> "Подключено: прямое" + hideSuffix()
                    VpnPath.Bypass -> "Подключено: обход" + hideSuffix()
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
            if (pendingHideIpSync || _ui.value.hideIp) {
                val want = _ui.value.hideIp
                pendingHideIpSync = false
                if (lastHideIpSent == want) {
                    AppLog.i(TAG, "Hide-IP already at hideIp=$want after tunnel up")
                } else {
                    val r = syncHideIpToProvision(want, viaVpn = true, tryVpnFallback = false)
                    if (r.isSuccess) {
                        lastHideIpSent = want
                    } else {
                        AppLog.e(TAG, "hide-ip post-tunnel sync failed: ${r.exceptionOrNull()?.message}")
                        if (want) {
                            _ui.value = _ui.value.copy(
                                lastError = "Скрытие адреса: ${r.exceptionOrNull()?.message}",
                            )
                        }
                    }
                }
            }
            scheduleEgressIpRefresh("tunnel-up")
            schedulePresenceHeartbeat()
        }
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
        if (failedPath == VpnPath.Bypass && isDeadCallMessage(message)) {
            return onDeadCallFailed(message)
        }
        val canFallback =
            pathMode == ConnPathMode.Auto &&
                failedPath == VpnPath.Direct &&
                callHashOrNull() != null &&
                _ui.value.state != ConnState.Disconnecting &&
                _ui.value.state != ConnState.Ready
        if (canFallback) {
            AppLog.i(TAG, "Direct failed — Auto fallback to Bypass: $message")
            applySessionPath(VpnPath.Bypass)
            _ui.value = _ui.value.copy(
                state = ConnState.Connecting,
                activePath = VpnPath.Bypass,
                statusText = "Прямое подключение недоступно. Выполняется переход на обход…",
                lastError = null,
                callRecreatePrompt = null,
                connectEnabled = true,
            )
            return TunnelFailureAction.SwitchToBypass
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
        if (callRecreateJob?.isActive == true) {
            AppLog.w(TAG, "Call recreate already in progress")
            return
        }
        callRecreateJob = scope.launch {
            recreateCallThenReconnect(holdService = holdService)
        }
    }

    private fun onDeadCallFailed(message: String): TunnelFailureAction {
        val action = decideDeadCallAction(
            silentRecreate = silentRecreate,
            hasVkSession = VkSession.hasSessionCookie(),
            recreateAttempts = callRecreateAttempts,
        )
        AppLog.i(TAG, "Dead VK call action=$action attempts=$callRecreateAttempts silent=$silentRecreate")
        return when (action) {
            DeadCallAction.SilentRecreate -> {
                startCallRecreate(holdService = true)
                TunnelFailureAction.HoldForCallRecreate
            }
            DeadCallAction.AskUser -> {
                failToError(
                    message = message,
                    prompt = CallRecreatePrompt.Ask,
                    status = "Звонок закрыт",
                )
                TunnelFailureAction.Stop
            }
            DeadCallAction.NeedVkLogin -> {
                failToError(
                    message = "Звонок закрыт. Войдите во ВКонтакте, чтобы создать новый код.",
                    prompt = CallRecreatePrompt.NeedLogin,
                    status = "Нужна авторизация ВКонтакте",
                )
                TunnelFailureAction.Stop
            }
            DeadCallAction.GiveUp -> {
                failToError("Не удалось обновить звонок. Создайте код вручную в настройках.")
                TunnelFailureAction.Stop
            }
        }
    }

    private suspend fun recreateCallThenReconnect(holdService: Boolean) {
        callRecreateAttempts++
        _ui.value = _ui.value.copy(
            state = ConnState.Connecting,
            activePath = VpnPath.Bypass,
            statusText = if (holdService) "Обновляю звонок…" else "Создаю новый звонок…",
            lastError = null,
            callRecreatePrompt = null,
            connectEnabled = true,
            softInfo = "Нужна сессия ВКонтакте на этом устройстве.",
        )
        if (!VkSession.hasSessionCookie()) {
            val login = runCatching { VkLoginActivity.login(appContext) }.getOrElse { Result.failure(it) }
            if (login.isFailure || !VkSession.hasSessionCookie()) {
                failToError(
                    message = login.exceptionOrNull()?.message
                        ?: "Авторизация ВКонтакте не выполнена.",
                    prompt = CallRecreatePrompt.NeedLogin,
                    status = "Нужна авторизация ВКонтакте",
                )
                if (holdService) stopTunnel()
                return
            }
        }
        val generated = VkCallHashGenerator.generateOne(appContext)
        val hash = generated.getOrNull()
        if (hash.isNullOrBlank()) {
            failToError(
                message = generated.exceptionOrNull()?.message
                    ?: "Не удалось создать новый звонок.",
                prompt = if (VkSession.hasSessionCookie()) {
                    CallRecreatePrompt.Ask
                } else {
                    CallRecreatePrompt.NeedLogin
                },
                status = "Не удалось обновить звонок",
            )
            if (holdService) stopTunnel()
            return
        }
        saveCallHash(hash)
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
            connect()
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
        _ui.value = cur.copy(
            state = ConnState.Error,
            activePath = null,
            statusText = status
                ?: if (wasSoft) "Не удалось переподключиться" else "Ошибка подключения",
            lastError = message,
            callRecreatePrompt = prompt,
            connectEnabled = connectAllowed(cur.probe),
        )
        scope.launch { stopTunnel() }
    }

    fun onServiceStopped() {
        if (softRestartInProgress) {
            AppLog.v(TAG, "Ignoring service stopped during soft restart")
            return
        }
        val cur = _ui.value
        if (
            cur.state == ConnState.Connected ||
            cur.state == ConnState.Connecting ||
            cur.state == ConnState.PausedTrustedWifi
        ) {
            _ui.value = cur.copy(
                state = ConnState.Ready,
                activePath = null,
                statusText = cur.probe?.message ?: "Отключено",
                softInfo = softInfoFor(cur.probe),
                connectEnabled = connectAllowed(cur.probe),
            )
        }
        runCatching {
            com.nonamevpn.app.TunnelWidgetProvider.updateWidgetState(
                appContext,
                running = false,
                statsText = null,
            )
            com.nonamevpn.app.QuickToggleTileService.requestTileUpdate(appContext)
            com.nonamevpn.app.AppShortcuts.refreshAsync(appContext)
        }
    }

    private fun connectAllowed(probe: ProbeResult?): Boolean {
        if (profile == null) return false
        return when (pathMode) {
            ConnPathMode.Direct, ConnPathMode.Bypass -> true
            ConnPathMode.Auto -> probe?.preselectedPath != null
        }
    }

    private fun applyProbe(result: ProbeResult) {
        _ui.value = _ui.value.copy(
            state = ConnState.Ready,
            probe = result,
            pathMode = pathMode,
            statusText = result.message,
            softInfo = softInfoFor(result),
            connectEnabled = connectAllowed(result),
            lastError = if (!connectAllowed(result)) result.message else null,
        )
        refreshHashFlag()
        Log.i(TAG, "probe class=${result.networkClass} path=${result.preselectedPath} ${result.elapsedMs}ms")
    }

    private fun softInfoFor(result: ProbeResult?): String? {
        val parts = mutableListOf<String>()
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
            NetworkClass.NeedBypass, NetworkClass.OpenNeedBypass ->
                if (pathMode == ConnPathMode.Auto) {
                    parts += "VPS недоступен — будет обход."
                }
            NetworkClass.NeedBypass ->
                if (pathMode == ConnPathMode.Auto) {
                    parts += "Похоже на белый список, VPS не отвечает — будет обход."
                }
            NetworkClass.DirectOk ->
                if (result.whitelistRestricted) {
                    parts += "Белый список, но VPS доступен — прямое."
                }
            NetworkClass.Captive ->
                parts += "Обнаружена страница авторизации сети. Сначала выполните вход в Wi‑Fi."
            else -> Unit
        }
        val needsHash = pathMode == ConnPathMode.Bypass ||
            (pathMode == ConnPathMode.Auto && result.preselectedPath == VpnPath.Bypass)
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
            val initialDelay = if (hideIp) 2_500L else 1_000L
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
                val p = profile
                val base = resolveProvisionUrl()
                if (p != null && !base.isNullOrBlank()) {
                    val ext = EgressIpProbe.current().orEmpty()
                    runCatching {
                        com.nonamevpn.app.deploy.ProvisionAdminApi.reportPresence(
                            baseUrl = base,
                            deviceId = p.deviceId,
                            name = p.name,
                            externalIp = ext,
                            deviceModel = PhoneModelLabel.current(),
                            appVersion = com.nonamevpn.app.BuildConfig.VERSION_NAME,
                            appVersionCode = com.nonamevpn.app.BuildConfig.VERSION_CODE,
                        )
                    }
                }
                delay(60_000L)
            }
        }
    }

    private fun handoverSessionAgeMs(): Long {
        if (tunnelStartedAtMs <= 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - tunnelStartedAtMs
    }

    private fun startTunnel(path: VpnPath) {
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
        )

        val intent = Intent(appContext, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_START
            putExtra(VpnTunnelService.EXTRA_PATH, path.name)
            putExtra(VpnTunnelService.EXTRA_HIDE_IP, false)
            putExtra(VpnTunnelService.EXTRA_TUN_ADDRESS, addr)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "startForegroundService failed: ${t.message}")
            throw t
        }
    }

    private fun stopTunnel() {
        presenceJob?.cancel()
        presenceJob = null
        AppLog.v(TAG, "Stop tunnel")
        EgressIpProbe.clear()
        TunnelSessionHolder.config = null
        val intent = Intent(appContext, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_STOP
        }
        appContext.startService(intent)
    }

    private suspend fun waitForBypassWorkers(): Boolean {
        val deadline = System.currentTimeMillis() + BYPASS_WORKERS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (TransportHealth.activeWorkers > 0) return true
            delay(BYPASS_WORKERS_POLL_MS)
        }
        return TransportHealth.activeWorkers > 0
    }

    companion object {
        private const val TAG = "ConnMgr"
        private const val DEFAULT_WORKERS = BypassWorkers.DEFAULT
        private const val BYPASS_WORKERS_WAIT_MS = 25_000L
        private const val BYPASS_WORKERS_POLL_MS = 250L
        private const val TRANSPORT_RESTART_DEBOUNCE_MS = 150L

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

package com.nonamevpn.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nonamevpn.app.bypass.CallHashStore
import com.nonamevpn.app.bypass.DialPath
import com.nonamevpn.app.profile.VpnProfile
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
)

class ConnectionManager(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(ConnUiState())
    val ui: StateFlow<ConnUiState> = _ui.asStateFlow()
    private val hashStore = CallHashStore(appContext)

    private var probeJob: Job? = null
    private var connectJob: Job? = null
    private var profile: VpnProfile? = null
    private var directEndpoint: String? = null
    private var provisionUrl: String? = null
    private var tunAddress: String? = null
    private var workers: Int = 3
    private var silentRecreate: Boolean = false
    private var dialPath: DialPath = DialPath.Auto
    private var pathMode: ConnPathMode = ConnPathMode.Auto
    /** Soft transport restart in progress (Wi‑Fi↔LTE); do not treat as user disconnect. */
    @Volatile private var softRestartInProgress: Boolean = false

    fun updateProfile(profile: VpnProfile?) {
        this.profile = profile
        if (profile != null) {
            directEndpoint = profile.direct.endpoint
            provisionUrl = profile.provisionBaseUrl
            tunAddress = when (profile.prefer) {
                "bypass" -> profile.bypass.address
                else -> profile.direct.address
            }
            workers = profile.bypass.workers.coerceIn(1, 9)
            refreshHashFlag()
        } else {
            directEndpoint = null
            provisionUrl = null
            tunAddress = null
            _ui.value = _ui.value.copy(hasCallHash = false)
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
    private var lastHideIpSent: Boolean? = null

    /** Hide-IP must reach provision after tunnel is up (Bypass / whitelist underlay). */
    @Volatile private var pendingHideIpSync: Boolean = false

    private fun hideIpViaVpn(): Boolean {
        val s = _ui.value.state
        return s == ConnState.Connected || s == ConnState.Connecting
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
        // Shade notification must reflect Hide-IP immediately (not only after soft-restart).
        refreshVpnNotification()
        pendingHideIpSync = false
        // Debounce rapid toggles — only the final value hits provision/WARP.
        hideIpSyncJob?.cancel()
        hideIpSyncJob = scope.launch {
            delay(900)
            if (_ui.value.hideIp != enabled) return@launch
            if (lastHideIpSent == enabled) {
                AppLog.i(TAG, "Hide-IP already synced hideIp=$enabled — skip")
                refreshVpnNotification()
                return@launch
            }
            val viaVpn = hideIpViaVpn()
            if (!viaVpn && enabled && _ui.value.probe?.provisionOk != true) {
                pendingHideIpSync = true
                AppLog.i(TAG, "Hide-IP queued until tunnel (provision unreachable on underlay)")
                return@launch
            }
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
                    AppLog.i(TAG, "Hide-IP applied — $why (soft-restart sockets)")
                    requestTransportRestart(why)
                }
            } else {
                AppLog.e(TAG, "hide-ip sync failed: ${r.exceptionOrNull()?.message}")
                if (viaVpn) {
                    _ui.value = _ui.value.copy(
                        lastError = "Скрытие IP не синхронизировано с VPS: ${r.exceptionOrNull()?.message}",
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
    fun requestTransportRestart(reason: String) {
        val state = _ui.value.state
        if (
            state != ConnState.Connected &&
            state != ConnState.PausedTrustedWifi &&
            state != ConnState.Connecting
        ) {
            return
        }
        val intent = Intent(appContext, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_RESTART_TRANSPORT)
            .putExtra(VpnTunnelService.EXTRA_RESTART_REASON, reason)
        runCatching { appContext.startService(intent) }
            .onFailure { AppLog.e(TAG, "restart transport failed: ${it.message}") }
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
        this.workers = workers.coerceIn(1, 9)
    }

    fun setSilentRecreate(enabled: Boolean) {
        silentRecreate = enabled
    }

    fun setDialPath(path: DialPath) {
        dialPath = path
    }

    fun setPathMode(mode: ConnPathMode) {
        pathMode = mode
        _ui.value = _ui.value.copy(
            pathMode = mode,
            softInfo = softInfoFor(_ui.value.probe),
        )
    }

    fun saveCallHash(hash: String) {
        val name = profile?.name ?: return
        val cleaned = com.nonamevpn.app.bypass.VkUrl.strip(hash)
        if (!com.nonamevpn.app.bypass.VkUrl.isPlausibleHash(cleaned)) return
        hashStore.setHash(name, cleaned)
        refreshHashFlag()
    }

    fun clearCallHash() {
        val name = profile?.name ?: return
        hashStore.clear(name)
        refreshHashFlag()
    }

    fun callHashOrNull(): String? = profile?.name?.let { hashStore.getHash(it) }

    private fun refreshHashFlag() {
        val name = profile?.name
        _ui.value = _ui.value.copy(
            hasCallHash = name != null && hashStore.hasHash(name),
        )
    }

    fun startInitialProbe() {
        val busy =
            _ui.value.state == ConnState.Connecting ||
                _ui.value.state == ConnState.Connected ||
                _ui.value.state == ConnState.PausedTrustedWifi ||
                _ui.value.state == ConnState.Disconnecting
        if (busy) {
            AppLog.w(TAG, "Probe skipped — tunnel busy (${_ui.value.state})")
            return
        }
        probeJob?.cancel()
        probeJob = scope.launch {
            AppLog.i(TAG, "Probe start endpoint=$directEndpoint provision=$provisionUrl")
            _ui.value = _ui.value.copy(
                state = ConnState.Probing,
                statusText = "Определение сети…",
                softInfo = null,
                connectEnabled = false,
                lastError = null,
            )
            val result = NetworkProbe.probe(appContext, directEndpoint, provisionUrl, quick = true)
            AppLog.i(
                TAG,
                "Probe done path=${result.preselectedPath} class=${result.networkClass} " +
                    "udp=${result.vpsUdpOk} health=${result.provisionOk} ${result.elapsedMs}ms",
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
        val current = _ui.value
        val mode = pathMode
        val probePreferred = current.probe?.preselectedPath
        if (current.state == ConnState.Probing || current.state == ConnState.Connecting) {
            AppLog.w(TAG, "Connect ignored (state=${current.state} mode=$mode)")
            return
        }
        if (current.state == ConnState.Connected) return
        if (mode == ConnPathMode.Auto && probePreferred == null) {
            AppLog.w(TAG, "Connect ignored (auto, no probe path)")
            return
        }
        if (profile == null) {
            AppLog.w(TAG, "Connect ignored — no profile")
            return
        }

        // Sync Hide-IP preference to VPS (policy route via warp0). WARP must be up.
        if (current.hideIp) {
            AppLog.i(TAG, "Hide-IP on — asking provision to route host via WARP")
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val snap = _ui.value
                val lastGood = snap.probe
                val labelPreferred = when (mode) {
                    ConnPathMode.Direct -> VpnPath.Direct
                    ConnPathMode.Bypass -> VpnPath.Bypass
                    ConnPathMode.Auto -> probePreferred ?: VpnPath.Direct
                }
                AppLog.i(TAG, "Connect requested mode=$mode preferred=$labelPreferred hideIp=${snap.hideIp}")
                _ui.value = snap.copy(
                    state = ConnState.Connecting,
                    statusText = "Подключение (${pathLabel(labelPreferred)})…",
                    connectEnabled = false,
                    lastError = null,
                )

                val deferHideIp =
                    snap.hideIp &&
                        snap.probe?.provisionOk != true &&
                        (labelPreferred == VpnPath.Bypass || snap.probe?.preselectedPath == VpnPath.Bypass)
                if (snap.hideIp && !deferHideIp) {
                    val r = syncHideIpToProvision(true, viaVpn = false)
                    if (r.isFailure) {
                        AppLog.e(TAG, "hide-ip enable failed: ${r.exceptionOrNull()?.message}")
                        _ui.value = _ui.value.copy(
                            state = ConnState.Error,
                            lastError = "Не удалось включить «Скрыть свой IP»: ${r.exceptionOrNull()?.message}",
                            connectEnabled = true,
                        )
                        return@launch
                    }
                    lastHideIpSent = true
                } else if (deferHideIp) {
                    pendingHideIpSync = true
                    AppLog.i(TAG, "Hide-IP deferred until Bypass tunnel (underlay cannot reach provision)")
                } else {
                    runCatching { syncHideIpToProvision(false, viaVpn = false) }
                        .onSuccess { lastHideIpSent = false }
                }
                // Soft re-probe for Auto/Direct stickiness. Forced Bypass still probes for UI status.
                var fresh = NetworkProbe.probe(appContext, directEndpoint, provisionUrl, quick = true)
                if (
                    mode == ConnPathMode.Auto &&
                    probePreferred == VpnPath.Direct &&
                    lastGood?.networkClass == NetworkClass.DirectOk &&
                    fresh.preselectedPath == VpnPath.Bypass &&
                    !fresh.provisionOk
                ) {
                    AppLog.w(TAG, "Connect re-probe flaked health — retry once")
                    fresh = NetworkProbe.probe(appContext, directEndpoint, provisionUrl, quick = true)
                }
                val usePath = resolveConnectPath(mode, probePreferred, lastGood, fresh)
                AppLog.i(
                    TAG,
                    "Connect re-probe path=${fresh.preselectedPath} → use=$usePath " +
                        "mode=$mode health=${fresh.provisionOk} udp=${fresh.vpsUdpOk}",
                )
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
                    AppLog.e(TAG, "Profile uses documentation IP (demo) — import real smoke JSON")
                    _ui.value = _ui.value.copy(
                        state = ConnState.Error,
                        probe = fresh,
                        lastError = "Профиль demo с фейковым IP (203.0.113.x). Импортируйте smoke JSON с VPS.",
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
                        lastError = "Для обхода нужен hash звонка (сохраните на этом телефоне)",
                        connectEnabled = true,
                        softInfo = softInfoFor(fresh),
                    )
                    return@launch
                }
                startTunnel(usePath)
                AppLog.i(TAG, "VpnTunnelService start path=$usePath")
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
                AppLog.i(TAG, "Connect cancelled")
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

    /**
     * Respect Settings path mode. In Auto, stick to a recent DirectOk when Connect
     * re-probe briefly loses /health (otherwise we bounce to Bypass without hash).
     */
    private fun resolveConnectPath(
        mode: ConnPathMode,
        probePreferred: VpnPath?,
        lastGood: ProbeResult?,
        fresh: ProbeResult,
    ): VpnPath? {
        when (mode) {
            ConnPathMode.Direct -> return VpnPath.Direct
            ConnPathMode.Bypass -> return VpnPath.Bypass
            ConnPathMode.Auto -> Unit
        }
        val freshPath = fresh.preselectedPath
        if (freshPath == VpnPath.Direct) return VpnPath.Direct
        if (
            probePreferred == VpnPath.Direct &&
            lastGood?.networkClass == NetworkClass.DirectOk &&
            (lastGood.provisionOk || lastGood.vpsUdpOk) &&
            freshPath == VpnPath.Bypass
        ) {
            AppLog.w(TAG, "Keeping Direct despite flaky re-probe (last health=${lastGood.provisionOk})")
            return VpnPath.Direct
        }
        return freshPath ?: probePreferred.takeIf {
            lastGood?.networkClass == NetworkClass.DirectOk || lastGood?.preselectedPath != null
        }
    }

    fun reportUserError(message: String) {
        AppLog.w(TAG, message)
        _ui.value = _ui.value.copy(
            state = ConnState.Error,
            lastError = message,
            statusText = "Нужно действие",
            connectEnabled = connectAllowed(_ui.value.probe),
        )
    }

    fun disconnect() {
        if (
            _ui.value.state != ConnState.Connected &&
            _ui.value.state != ConnState.Connecting &&
            _ui.value.state != ConnState.PausedTrustedWifi
        ) {
            return
        }
        softRestartInProgress = false
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            _ui.value = _ui.value.copy(
                state = ConnState.Disconnecting,
                statusText = "Отключение…",
                connectEnabled = false,
                lastError = null,
            )
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
            )
        }
    }

    fun onServiceStarted(path: VpnPath) {
        Log.i(TAG, "VpnService started path=$path")
    }

    /** WDTT-Plus-style soft reconnect: keep Connected UI, show progress. */
    fun onTransportRestarting(reason: String) {
        softRestartInProgress = true
        val path = _ui.value.activePath
        AppLog.i(TAG, "Transport soft restart: $reason")
        val status = when {
            reason.startsWith("Hide-IP") -> "Смена выхода — переподключение…"
            reason.startsWith("[СЕТЬ]") -> "Сеть сменилась — переподключение…"
            else -> "Переподключение транспорта…"
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
     * After Wi‑Fi↔LTE settle: re-classify underlay (whitelist / Direct) and
     * either soft-restart the same path or switch Direct↔Bypass in Auto mode.
     *
     * [bindNetwork] must be the real underlay (NOT_VPN); probing through the
     * tunnel would falsely report Direct while on Bypass.
     */
    suspend fun decideNetworkHandover(
        bindNetwork: android.net.Network?,
    ): NetworkHandoverDecision {
        val currentPath = TunnelSessionHolder.config?.path
            ?: _ui.value.activePath
            ?: return NetworkHandoverDecision.SoftRestartSamePath
        val mode = pathMode
        if (mode != ConnPathMode.Auto) {
            AppLog.i(TAG, "Handover: mode=$mode — soft-restart $currentPath (no re-probe)")
            return NetworkHandoverDecision.SoftRestartSamePath
        }

        val base = resolveProvisionUrl()
        AppLog.i(
            TAG,
            "Handover probe start path=$currentPath endpoint=$directEndpoint provision=$base " +
                "bind=${bindNetwork?.networkHandle}",
        )
        val fresh = NetworkProbe.probe(
            context = appContext,
            directEndpoint = directEndpoint,
            provisionBaseUrl = base,
            bindNetwork = bindNetwork,
            quick = true,
        )
        AppLog.i(
            TAG,
            "Handover probe done class=${fresh.networkClass} path=${fresh.preselectedPath} " +
                "yandex=${fresh.yandexOk} bigtech=${fresh.bigtechOk} " +
                "udp=${fresh.vpsUdpOk} health=${fresh.provisionOk} ${fresh.elapsedMs}ms",
        )

        // Update UI probe snapshot without leaving Connected/Connecting.
        _ui.value = _ui.value.copy(
            probe = fresh,
            softInfo = softInfoFor(fresh),
            hasCallHash = profile?.name?.let { hashStore.hasHash(it) } == true,
        )

        val bypassAllowed = profile?.name?.let { hashStore.hasHash(it) } == true
        val decision = decideNetworkHandoverAction(
            pathMode = mode,
            currentPath = currentPath,
            probedPath = fresh.preselectedPath,
            bypassAllowed = bypassAllowed,
        )
        when (decision) {
            is NetworkHandoverDecision.SwitchPath -> {
                AppLog.i(TAG, "Handover: switch $currentPath → ${decision.path}")
                applySessionPath(decision.path)
                softRestartInProgress = true
                _ui.value = _ui.value.copy(
                    state = ConnState.Connecting,
                    activePath = decision.path,
                    statusText = "Сеть сменилась — путь ${pathLabel(decision.path)}…",
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
                    AppLog.i(TAG, "Handover: keep $currentPath (probe=${fresh.preselectedPath})")
                }
            }
        }
        return decision
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

    fun onUnderlyingNetworkLost() {
        if (_ui.value.state != ConnState.Connected && _ui.value.state != ConnState.Connecting) return
        scope.launch {
            _ui.value = _ui.value.copy(
                statusText = "Ожидание сети…",
                softInfo = "Подключение восстановится на новой Wi‑Fi/LTE.",
            )
        }
    }

    fun onTrustedWifiWaiting(ssid: String) {
        softRestartInProgress = false
        scope.launch {
            _ui.value = _ui.value.copy(
                state = ConnState.PausedTrustedWifi,
                statusText = "VPN выключен в «$ssid»",
                softInfo = "Выйдите из доверенной сети — VPN поднимется сам.",
                connectEnabled = true,
                lastError = null,
            )
        }
    }

    fun onTrustedWifiResuming() {
        scope.launch {
            _ui.value = _ui.value.copy(
                state = ConnState.Connecting,
                statusText = "Выход из доверенной сети — подключение…",
                softInfo = null,
                connectEnabled = true,
                lastError = null,
            )
        }
    }

    fun onTunnelRunning(path: VpnPath) {
        scope.launch {
            softRestartInProgress = false
            _ui.value = _ui.value.copy(
                state = ConnState.Connected,
                activePath = path,
                statusText = when (path) {
                    VpnPath.Direct -> "Подключено: прямое" + hideSuffix()
                    VpnPath.Bypass -> "Подключено: обход" + hideSuffix()
                },
                connectEnabled = true,
                lastError = null,
                softInfo = softInfoFor(_ui.value.probe),
            )
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
                                lastError = "Скрыть свой IP: ${r.exceptionOrNull()?.message}",
                            )
                        }
                    }
                }
            }
        }
    }

    fun onTunnelFailed(message: String) {
        // Ignore cancel noise if UI already left the tunnel (Stop / reconnect).
        if (
            message.contains("cancelled", ignoreCase = true) ||
            message.contains("StandaloneCoroutine", ignoreCase = true)
        ) {
            AppLog.w(TAG, "Ignoring cancel as tunnel failure: $message")
            return
        }
        val wasSoft = softRestartInProgress
        softRestartInProgress = false
        scope.launch {
            if (_ui.value.state == ConnState.Disconnecting || _ui.value.state == ConnState.Ready) {
                AppLog.w(TAG, "Ignoring late tunnel failure in ${_ui.value.state}: $message")
                return@launch
            }
            stopTunnel()
            _ui.value = _ui.value.copy(
                state = ConnState.Error,
                activePath = null,
                statusText = if (wasSoft) "Не удалось переподключиться" else "Ошибка подключения",
                lastError = message,
                connectEnabled = connectAllowed(_ui.value.probe),
            )
        }
    }

    fun onServiceStopped() {
        if (softRestartInProgress) {
            AppLog.i(TAG, "Ignoring service stopped during soft restart")
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
            ConnPathMode.Direct -> parts += "Режим: только прямое (AmneziaWG)."
            ConnPathMode.Bypass -> parts += "Режим: только обход (RAW / звонок)."
            ConnPathMode.Auto -> Unit
        }
        if (result == null) {
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
        if (isDocumentationHost(directEndpoint) || isDocumentationHost(profile?.bypass?.peer)) {
            parts += "Сейчас demo-профиль с фейковым IP — импортируйте smoke JSON (159.194.225.162)."
        }
        when (result.networkClass) {
            NetworkClass.OpenNeedBypass ->
                if (pathMode == ConnPathMode.Auto) {
                    parts += "Сеть открыта, но VPS health/UDP не подтвердили Direct — будет обход."
                }
            NetworkClass.DirectOk ->
                if (result.provisionOk && !result.vpsUdpOk && pathMode != ConnPathMode.Bypass) {
                    parts += "AWG не отвечает на «пустой» UDP (так и должно быть). Direct доступен по health VPS."
                }
            NetworkClass.Captive ->
                parts += "Похоже на captive portal — сначала войдите в Wi‑Fi."
            else -> Unit
        }
        val needsHash = pathMode == ConnPathMode.Bypass ||
            (pathMode == ConnPathMode.Auto && result.preselectedPath == VpnPath.Bypass)
        if (needsHash && !_ui.value.hasCallHash) {
            parts += "Для обхода сохраните hash звонка на телефоне."
        }
        if (_ui.value.hideIp) {
            parts += "Скрыть свой IP: выход через Cloudflare, не с адреса VPS."
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun pathLabel(path: VpnPath): String = when (path) {
        VpnPath.Direct -> "прямое"
        VpnPath.Bypass -> "обход"
    }

    private fun hideSuffix(enabled: Boolean = _ui.value.hideIp): String =
        if (enabled) " · IP скрыт" else ""

    /**
     * Two-line shade notification body.
     * [Pair.first] = live line (speed/traffic/time/IP);
     * [Pair.second] = modes line (path + hide-IP).
     */
    fun notificationContent(
        sessionStartedAtMs: Long = 0L,
        tunIp: String = "—",
    ): Pair<String, String> {
        val u = _ui.value
        if (u.state == ConnState.PausedTrustedWifi) {
            val t = u.statusText.ifBlank { "VPN выключен в доверенной сети" }
            return t to "Доверенная Wi‑Fi"
        }
        if (softRestartInProgress || u.state == ConnState.Connecting) {
            val t = u.statusText.ifBlank { "Переподключение…" }
            return t to "…"
        }

        val down = VpnLiveStats.formatRate(VpnLiveStats.downBps)
        val up = VpnLiveStats.formatRate(VpnLiveStats.upBps)
        val rx = VpnLiveStats.formatBytes(VpnLiveStats.totalRx)
        val tx = VpnLiveStats.formatBytes(VpnLiveStats.totalTx)
        val dur = VpnLiveStats.formatDuration(sessionStartedAtMs)
        val live = "↓$down ↑$up · ↓$rx ↑$tx · $dur · $tunIp"
        val pathMode = when (u.activePath) {
            VpnPath.Direct -> "Прямое · AWG"
            VpnPath.Bypass -> "Обход · RAW"
            null -> "—"
        }
        val hideMode = if (u.hideIp) "Скрыть IP: вкл" else "Скрыть IP: выкл"
        val modes = "$pathMode · $hideMode"
        return live to modes
    }

    fun notificationRunningText(): String = notificationContent().first

    private fun startTunnel(path: VpnPath) {
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
        AppLog.i(TAG, "Stop tunnel")
        TunnelSessionHolder.config = null
        val intent = Intent(appContext, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_STOP
        }
        appContext.startService(intent)
    }

    companion object {
        private const val TAG = "ConnMgr"

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

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    Disconnecting,
    Error,
}

data class ConnUiState(
    val state: ConnState = ConnState.Idle,
    val probe: ProbeResult? = null,
    val activePath: VpnPath? = null,
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
    private var profile: VpnProfile? = null
    private var directEndpoint: String? = null
    private var provisionUrl: String? = null
    private var tunAddress: String? = null
    private var workers: Int = 3
    private var silentRecreate: Boolean = false
    private var dialPath: DialPath = DialPath.Auto

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

    fun setHideIp(enabled: Boolean) {
        _ui.value = _ui.value.copy(hideIp = enabled)
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
            val result = NetworkProbe.probe(appContext, directEndpoint, provisionUrl)
            AppLog.i(
                TAG,
                "Probe done path=${result.preselectedPath} class=${result.networkClass} " +
                    "udp=${result.vpsUdpOk} health=${result.provisionOk} ${result.elapsedMs}ms",
            )
            applyProbe(result)
        }
    }

    fun connect() {
        val current = _ui.value
        val path = current.probe?.preselectedPath
        if (path == null || current.state == ConnState.Probing || current.state == ConnState.Connecting) {
            AppLog.w(TAG, "Connect ignored (state=${current.state} path=$path)")
            return
        }
        if (current.state == ConnState.Connected) return

        // WARP stub: don't block forever — auto-off and continue.
        if (current.hideIp) {
            AppLog.w(TAG, "Hide-IP (WARP stub) was on — auto-off for Connect")
            _ui.value = current.copy(hideIp = false)
        }

        scope.launch {
            try {
                val snap = _ui.value
                AppLog.i(TAG, "Connect requested preferred=$path")
                _ui.value = snap.copy(
                    state = ConnState.Connecting,
                    statusText = "Подключение (${pathLabel(path)})…",
                    connectEnabled = false,
                    lastError = null,
                )
                val fresh = NetworkProbe.probe(appContext, directEndpoint, provisionUrl)
                AppLog.i(
                    TAG,
                    "Connect re-probe path=${fresh.preselectedPath} health=${fresh.provisionOk} udp=${fresh.vpsUdpOk}",
                )
                if (fresh.preselectedPath == null) {
                    applyProbe(fresh)
                    _ui.value = _ui.value.copy(
                        state = ConnState.Error,
                        lastError = fresh.message,
                        connectEnabled = false,
                    )
                    AppLog.e(TAG, "Connect aborted: ${fresh.message}")
                    return@launch
                }
                val usePath = fresh.preselectedPath
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
                _ui.value = _ui.value.copy(
                    state = ConnState.Connecting,
                    activePath = usePath,
                    probe = fresh,
                    softInfo = softInfoFor(fresh),
                    statusText = "Запуск туннеля (${pathLabel(usePath)})…",
                    connectEnabled = false,
                )
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
            statusText = "Нужно действие",
            connectEnabled = _ui.value.probe?.preselectedPath != null,
        )
    }

    fun disconnect() {
        if (_ui.value.state != ConnState.Connected && _ui.value.state != ConnState.Connecting) return
        scope.launch {
            _ui.value = _ui.value.copy(
                state = ConnState.Disconnecting,
                statusText = "Отключение…",
                connectEnabled = false,
            )
            stopTunnel()
            _ui.value = _ui.value.copy(
                state = ConnState.Ready,
                activePath = null,
                statusText = _ui.value.probe?.message ?: "Готово",
                softInfo = softInfoFor(_ui.value.probe),
                connectEnabled = _ui.value.probe?.preselectedPath != null,
            )
        }
    }

    fun onServiceStarted(path: VpnPath) {
        Log.i(TAG, "VpnService started path=$path")
    }

    fun onTunnelRunning(path: VpnPath) {
        scope.launch {
            _ui.value = _ui.value.copy(
                state = ConnState.Connected,
                activePath = path,
                statusText = when (path) {
                    VpnPath.Direct -> "Подключено: прямое" + hideSuffix()
                    VpnPath.Bypass -> "Подключено: обход" + hideSuffix()
                },
                connectEnabled = true,
                lastError = null,
            )
        }
    }

    fun onTunnelFailed(message: String) {
        scope.launch {
            stopTunnel()
            _ui.value = _ui.value.copy(
                state = ConnState.Error,
                activePath = null,
                statusText = "Ошибка подключения",
                lastError = message,
                connectEnabled = _ui.value.probe?.preselectedPath != null,
            )
        }
    }

    fun onServiceStopped() {
        val cur = _ui.value
        if (cur.state == ConnState.Connected || cur.state == ConnState.Connecting) {
            _ui.value = cur.copy(
                state = ConnState.Ready,
                activePath = null,
                statusText = cur.probe?.message ?: "Отключено",
                softInfo = softInfoFor(cur.probe),
                connectEnabled = cur.probe?.preselectedPath != null,
            )
        }
    }

    private fun applyProbe(result: ProbeResult) {
        val enabled = result.preselectedPath != null
        _ui.value = _ui.value.copy(
            state = ConnState.Ready,
            probe = result,
            statusText = result.message,
            softInfo = softInfoFor(result),
            connectEnabled = enabled,
            lastError = if (!enabled) result.message else null,
        )
        refreshHashFlag()
        Log.i(TAG, "probe class=${result.networkClass} path=${result.preselectedPath} ${result.elapsedMs}ms")
    }

    private fun softInfoFor(result: ProbeResult?): String? {
        if (result == null) return null
        val parts = mutableListOf<String>()
        if (isDocumentationHost(directEndpoint) || isDocumentationHost(profile?.bypass?.peer)) {
            parts += "Сейчас demo-профиль с фейковым IP — импортируйте smoke JSON (159.194.225.162)."
        }
        when (result.networkClass) {
            NetworkClass.OpenNeedBypass ->
                parts += "Сеть открыта, но VPS health/UDP не подтвердили Direct — будет обход."
            NetworkClass.DirectOk ->
                if (result.provisionOk && !result.vpsUdpOk) {
                    parts += "AWG не отвечает на «пустой» UDP (так и должно быть). Direct доступен по health VPS."
                }
            NetworkClass.Captive ->
                parts += "Похоже на captive portal — сначала войдите в Wi‑Fi."
            else -> Unit
        }
        if (result.preselectedPath == VpnPath.Bypass && !_ui.value.hasCallHash) {
            parts += "Для обхода сохраните hash звонка на телефоне."
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun pathLabel(path: VpnPath): String = when (path) {
        VpnPath.Direct -> "прямое"
        VpnPath.Bypass -> "обход"
    }

    private fun hideSuffix(): String =
        if (_ui.value.hideIp) " · IP скрыт (WARP)" else ""

    private fun startTunnel(path: VpnPath) {
        val addr = when (path) {
            VpnPath.Direct -> profile?.direct?.address ?: tunAddress
            VpnPath.Bypass -> profile?.bypass?.address ?: tunAddress
        } ?: "10.8.0.2"

        TunnelSessionHolder.config = TunnelSessionConfig(
            path = path,
            profile = profile,
            tunAddress = addr,
            hideIp = false, // WARP stub — never pass hideIp into backends yet
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

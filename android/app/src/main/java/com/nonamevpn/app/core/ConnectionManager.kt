package com.nonamevpn.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
)

/**
 * Orchestrates probe → preselect → connect stub.
 * Real AWG/RAW backends plug into [startTunnel]/[stopTunnel] later.
 */
class ConnectionManager(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(ConnUiState())
    val ui: StateFlow<ConnUiState> = _ui.asStateFlow()

    private var probeJob: Job? = null
    private var directEndpoint: String? = null
    private var provisionUrl: String? = null

    fun updateEndpoints(directEndpoint: String?, provisionUrl: String?) {
        this.directEndpoint = directEndpoint
        this.provisionUrl = provisionUrl
    }

    fun setHideIp(enabled: Boolean) {
        _ui.value = _ui.value.copy(hideIp = enabled)
    }

    fun startInitialProbe() {
        probeJob?.cancel()
        probeJob = scope.launch {
            _ui.value = _ui.value.copy(
                state = ConnState.Probing,
                statusText = "Определение сети…",
                softInfo = null,
                connectEnabled = false,
                lastError = null,
            )
            val result = NetworkProbe.probe(appContext, directEndpoint, provisionUrl)
            applyProbe(result)
        }
    }

    fun connect() {
        val current = _ui.value
        val path = current.probe?.preselectedPath
        if (path == null || current.state == ConnState.Probing || current.state == ConnState.Connecting) {
            return
        }
        if (current.state == ConnState.Connected) return

        scope.launch {
            _ui.value = current.copy(
                state = ConnState.Connecting,
                statusText = "Подключение (${pathLabel(path)})…",
                connectEnabled = false,
            )
            // Fresh short re-probe before bring-up
            val fresh = NetworkProbe.probe(appContext, directEndpoint, provisionUrl)
            if (fresh.preselectedPath == null) {
                applyProbe(fresh)
                _ui.value = _ui.value.copy(
                    state = ConnState.Error,
                    lastError = fresh.message,
                    connectEnabled = false,
                )
                return@launch
            }
            val usePath = fresh.preselectedPath
            startTunnel(usePath, _ui.value.hideIp)
            // Connected confirmation arrives via onServiceStarted; optimistic UI:
            _ui.value = _ui.value.copy(
                state = ConnState.Connected,
                activePath = usePath,
                probe = fresh,
                softInfo = softInfoFor(fresh),
                statusText = when (usePath) {
                    VpnPath.Direct -> "Подключено: прямое" + hideSuffix()
                    VpnPath.Bypass -> "Подключено: обход" + hideSuffix()
                },
                connectEnabled = true,
            )
        }
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
        Log.i(TAG, "probe class=${result.networkClass} path=${result.preselectedPath} ${result.elapsedMs}ms")
    }

    /** Soft info only — never blocks Connect (unlike old qWDTT whitelist dialog). */
    private fun softInfoFor(result: ProbeResult?): String? {
        if (result == null) return null
        return when (result.networkClass) {
            NetworkClass.OpenNeedBypass ->
                "Сеть выглядит открытой, но VPS по UDP не ответил — будет обход. Это не ошибка."
            NetworkClass.Captive ->
                "Похоже на captive portal — сначала войдите в Wi‑Fi."
            else -> null
        }
    }

    private fun pathLabel(path: VpnPath): String = when (path) {
        VpnPath.Direct -> "прямое"
        VpnPath.Bypass -> "обход"
    }

    private fun hideSuffix(): String =
        if (_ui.value.hideIp) " · IP скрыт (WARP)" else ""

    private fun startTunnel(path: VpnPath, hideIp: Boolean) {
        val intent = Intent(appContext, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_START
            putExtra(VpnTunnelService.EXTRA_PATH, path.name)
            putExtra(VpnTunnelService.EXTRA_HIDE_IP, hideIp)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private fun stopTunnel() {
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

        /** For VpnService callbacks after [get] was initialized from UI/Application. */
        fun getOrNull(): ConnectionManager? = instance
    }
}

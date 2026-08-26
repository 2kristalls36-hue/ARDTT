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
        hashStore.setHash(name, hash)
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
            if (usePath == VpnPath.Bypass && callHashOrNull().isNullOrBlank()) {
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
            // Optimistic until backend reports Running/Failed
            _ui.value = _ui.value.copy(
                state = ConnState.Connecting,
                activePath = usePath,
                probe = fresh,
                softInfo = softInfoFor(fresh),
                statusText = "Запуск туннеля (${pathLabel(usePath)})…",
                connectEnabled = false,
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
        when (result.networkClass) {
            NetworkClass.OpenNeedBypass ->
                parts += "Сеть выглядит открытой, но VPS по UDP не ответил — будет обход. Это не ошибка."
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
            hideIp = _ui.value.hideIp,
            callHash = callHashOrNull(),
            workers = workers,
            silentRecreate = silentRecreate,
            dialPathName = dialPath.name,
        )

        val intent = Intent(appContext, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_START
            putExtra(VpnTunnelService.EXTRA_PATH, path.name)
            putExtra(VpnTunnelService.EXTRA_HIDE_IP, _ui.value.hideIp)
            putExtra(VpnTunnelService.EXTRA_TUN_ADDRESS, addr)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private fun stopTunnel() {
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
    }
}

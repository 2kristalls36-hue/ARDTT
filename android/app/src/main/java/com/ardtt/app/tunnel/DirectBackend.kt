package com.ardtt.app.tunnel

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.VpnLiveStats
import com.ardtt.app.core.VpnPath
import com.ardtt.app.core.VpnTunnelService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import org.amnezia.awg.GoBackend
import org.amnezia.awg.util.SharedLibraryLoader

/**
 * Path A — AmneziaWG 2.0 userspace ([libwg-go] via [GoBackend] JNI).
 * Owns the TUN FD after [ParcelFileDescriptor.detachFd]; [awgTurnOff] closes it.
 */
class DirectBackend : TunnelBackend {
    override val path: VpnPath = VpnPath.Direct
    @Volatile private var stopped = false
    private val handle = AtomicInteger(-1)

    override suspend fun start(
        service: VpnService,
        tun: ParcelFileDescriptor?,
        config: TunnelSessionConfig,
        onState: (TunnelBackendState) -> Unit,
    ) {
        stopped = false
        onState(TunnelBackendState.Starting)
        if (tun == null) {
            onState(TunnelBackendState.Failed("Нет TUN для Direct"))
            return
        }
        val direct = config.profile?.direct
        if (direct == null ||
            direct.privateKey.isBlank() ||
            direct.peerPublicKey.isBlank() ||
            direct.endpoint.isBlank()
        ) {
            onState(TunnelBackendState.Failed("Нет ключей AWG в профиле — обновите профиль с provision"))
            return
        }

        val goConfig = try {
            AwgUserspaceConfig.build(direct)
        } catch (e: Exception) {
            Log.e(TAG, "config", e)
            onState(TunnelBackendState.Failed(e.message ?: "Ошибка конфига AWG"))
            return
        }

        try {
            SharedLibraryLoader.loadSharedLibrary(service, "wg-go")
        } catch (e: Exception) {
            Log.e(TAG, "load libwg-go", e)
            onState(TunnelBackendState.Failed("Не удалось загрузить AmneziaWG (.so)"))
            return
        }

        val version = runCatching { GoBackend.awgVersion() }.getOrNull()
        AppLog.i(TAG, "awg version=$version endpoint=${direct.endpoint} hideIp=${config.hideIp}")

        val tunFd = try {
            tun.detachFd()
        } catch (e: Exception) {
            Log.e(TAG, "detachFd", e)
            onState(TunnelBackendState.Failed("Не удалось передать TUN в AWG"))
            return
        }

        val tunService = service as? VpnTunnelService
        var processPin = "skip"
        // Capture observation baseline before awgTurnOn so an early handshake is not lost.
        val opBaseline = VpnLiveStats.beginDirectOperation()
        AppLog.i(TAG, "direct_op_started id=${opBaseline.operationId}")
        val h = try {
            processPin = tunService?.pinProcessToUnderlay() ?: "no-service"
            val started = GoBackend.awgTurnOn(IFACE, tunFd, goConfig)
            if (started >= 0) {
                handle.set(started)
                VpnLiveStats.setAwgHandle(started)
                val sock4 = GoBackend.awgGetSocketV4(started)
                val sock6 = GoBackend.awgGetSocketV6(started)
                protectAwgSocket(service, "v4", sock4)
                protectAwgSocket(service, "v6", sock6)
                val bind4 = bindAwgToUnderlay(service, sock4)
                val bind6 = bindAwgToUnderlay(service, sock6)
                AppLog.i(
                    TAG,
                    "tunnel up handle=$started op=${opBaseline.operationId} protect v4=$sock4 v6=$sock6 " +
                        "process=$processPin underlay v4=$bind4 v6=$bind6",
                )
                logAwgSnapshot(started, "up")
            }
            started
        } finally {
            tunService?.unpinProcessFromUnderlay()
        }
        if (h < 0) {
            Log.e(TAG, "awgTurnOn failed code=$h")
            AppLog.e(TAG, "awgTurnOn failed code=$h process=$processPin")
            // detachFd transferred ownership; close orphaned FD ourselves.
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
                .onFailure { Log.w(TAG, "close orphaned tunFd=$tunFd", it) }
            onState(TunnelBackendState.Failed("AmneziaWG не поднялся (код $h)"))
            return
        }

        onState(TunnelBackendState.Running)
        try {
            coroutineScope {
                var ticks = 0
                while (isActive && !stopped) {
                    delay(5_000)
                    ticks += 1
                    if (ticks == 1 || ticks % 6 == 0) {
                        logAwgSnapshot(h, "live")
                    }
                }
            }
        } finally {
            turnOff()
            onState(TunnelBackendState.Stopped)
        }
    }

    override fun stop() {
        stopped = true
        turnOff()
    }

    private fun bindAwgToUnderlay(service: VpnService, fd: Int): String {
        if (fd < 0) return "skip"
        val tun = service as? VpnTunnelService ?: return "no-service"
        return tun.bindSocketToUnderlay(fd)
    }

    private fun protectAwgSocket(service: VpnService, label: String, fd: Int) {
        if (fd < 0) return
        repeat(3) { attempt ->
            if (service.protect(fd)) {
                if (attempt > 0) {
                    AppLog.w(TAG, "protect $label fd=$fd ok after retry ${attempt + 1}")
                }
                return
            }
        }
        AppLog.w(TAG, "protect $label fd=$fd failed — AWG UDP may loop into the TUN")
    }

    private fun logAwgSnapshot(handle: Int, reason: String) {
        val cfg = runCatching { GoBackend.awgGetConfig(handle) }.getOrNull()
        if (cfg.isNullOrBlank()) {
            AppLog.w(TAG, "awg $reason handle=$handle config empty")
            return
        }
        val xfer = VpnLiveStats.parseAwgTransfer(cfg)
        val hs = VpnLiveStats.parseAwgHandshakeSec(cfg)
        AppLog.i(
            TAG,
            "awg $reason rx=${xfer?.first ?: -1} tx=${xfer?.second ?: -1} handshake_sec=$hs",
        )
    }

    private fun turnOff() {
        // awgTurnOff must run once per handle; stop() and coroutine finally may race.
        val h = handle.getAndSet(-1)
        if (h < 0) return
        VpnLiveStats.clearAwgHandle(h)
        runCatching { GoBackend.awgTurnOff(h) }
            .onFailure { Log.w(TAG, "awgTurnOff", it) }
        Log.i(TAG, "tunnel down handle=$h")
    }

    companion object {
        private const val TAG = "DirectBackend"
        private const val IFACE = "ardtt0"
    }
}

package com.nonamevpn.app.tunnel

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nonamevpn.app.core.VpnLiveStats
import com.nonamevpn.app.core.VpnPath
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
        Log.i(TAG, "awg version=$version endpoint=${direct.endpoint} hideIp=${config.hideIp}")

        val tunFd = try {
            tun.detachFd()
        } catch (e: Exception) {
            Log.e(TAG, "detachFd", e)
            onState(TunnelBackendState.Failed("Не удалось передать TUN в AWG"))
            return
        }

        val h = GoBackend.awgTurnOn(IFACE, tunFd, goConfig)
        if (h < 0) {
            Log.e(TAG, "awgTurnOn failed code=$h")
            // detachFd transferred ownership; close orphaned FD ourselves.
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
                .onFailure { Log.w(TAG, "close orphaned tunFd=$tunFd", it) }
            onState(TunnelBackendState.Failed("AmneziaWG не поднялся (код $h)"))
            return
        }
        handle.set(h)
        VpnLiveStats.setAwgHandle(h)

        val sock4 = GoBackend.awgGetSocketV4(h)
        val sock6 = GoBackend.awgGetSocketV6(h)
        if (sock4 >= 0) service.protect(sock4)
        if (sock6 >= 0) service.protect(sock6)
        Log.i(TAG, "tunnel up handle=$h protect v4=$sock4 v6=$sock6")

        onState(TunnelBackendState.Running)
        try {
            coroutineScope {
                while (isActive && !stopped) {
                    delay(30_000)
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
        private const val IFACE = "nvpn0"
    }
}

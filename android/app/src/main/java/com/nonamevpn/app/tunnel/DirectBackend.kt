package com.nonamevpn.app.tunnel

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nonamevpn.app.core.VpnPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope

/**
 * Path A — AmneziaWG 2.0. Tun FD is ready; userspace AWG (.so / GoBackend) plugs in next.
 * Until then we hold the session open so probe/Connect UX and VpnService lifecycle work.
 */
class DirectBackend : TunnelBackend {
    override val path: VpnPath = VpnPath.Direct
    @Volatile private var stopped = false

    override suspend fun start(
        service: VpnService,
        tun: ParcelFileDescriptor,
        config: TunnelSessionConfig,
        onState: (TunnelBackendState) -> Unit,
    ) {
        stopped = false
        onState(TunnelBackendState.Starting)
        val endpoint = config.profile?.direct?.endpoint
        val hasKeys = !config.profile?.direct?.privateKey.isNullOrBlank() &&
            !config.profile?.direct?.peerPublicKey.isNullOrBlank()
        Log.i(TAG, "direct start endpoint=$endpoint keys=$hasKeys hideIp=${config.hideIp}")
        if (!hasKeys) {
            onState(TunnelBackendState.Failed("Нет ключей AWG в профиле — обновите профиль с provision"))
            return
        }
        // Native amneziawg-go / GoBackend bind to [tun] here.
        onState(TunnelBackendState.Running)
        coroutineScope {
            while (isActive && !stopped) {
                delay(30_000)
            }
        }
        onState(TunnelBackendState.Stopped)
    }

    override fun stop() {
        stopped = true
    }

    companion object {
        private const val TAG = "DirectBackend"
    }
}

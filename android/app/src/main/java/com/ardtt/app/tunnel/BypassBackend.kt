package com.ardtt.app.tunnel

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ardtt.app.bypass.BypassConfig
import com.ardtt.app.bypass.BypassPhase
import com.ardtt.app.bypass.BypassSession
import com.ardtt.app.bypass.DialPath
import com.ardtt.app.core.BypassWorkers
import com.ardtt.app.core.VpnPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope

/**
 * Path B — RAW over TURN via qWDTT/SpaceNeuroX go_client (libclient.so):
 * dial (vkcalls) → TURN TCP → WRAP → VPS -listen-raw. No DTLS. No nested WG.
 *
 * TUN is established after RAWCONF (not before), so go_client can dial without
 * routing its own sockets into the VPN.
 */
class BypassBackend(
    private val session: BypassSession = BypassSession(),
) : TunnelBackend {
    override val path: VpnPath = VpnPath.Bypass

    override suspend fun start(
        service: VpnService,
        tun: ParcelFileDescriptor?,
        config: TunnelSessionConfig,
        onState: (TunnelBackendState) -> Unit,
    ) {
        // Path B opens TUN after RAWCONF. Soft restart may keep the existing
        // VpnService fd; do not close a pre-created descriptor here.

        val profile = config.profile
        if (profile == null) {
            onState(TunnelBackendState.Failed("Нет профиля"))
            return
        }
        val hash = config.callHash
        if (hash.isNullOrBlank()) {
            onState(TunnelBackendState.Failed("Для обхода необходимо сохранить код звонка в настройках"))
            return
        }
        if (profile.bypass.password.isBlank() || profile.bypass.peer.isBlank()) {
            onState(TunnelBackendState.Failed("В профиле отсутствуют параметры обхода."))
            return
        }

        onState(TunnelBackendState.Starting)
        val dialPath = runCatching { DialPath.valueOf(config.dialPathName) }.getOrDefault(DialPath.Auto)
        val done = CompletableDeferred<TunnelBackendState>()

        coroutineScope {
            session.start(
                scope = this,
                service = service,
                config = BypassConfig(
                    profile = profile,
                    callHash = hash,
                    workers = config.workers.coerceIn(BypassWorkers.MIN, BypassWorkers.MAX),
                    dialPath = dialPath,
                    silentRecreate = config.silentRecreate,
                    hideIp = config.hideIp,
                ),
                establishTun = { ip, dnsCsv, mtu ->
                    (service as? TunEstablisher)?.establishTun(ip, dnsCsv, mtu)
                        ?: error("Не удалось открыть TUN")
                },
            ) { phase ->
                Log.i(TAG, "phase=$phase")
                when (phase) {
                    is BypassPhase.Running -> onState(TunnelBackendState.Running)
                    is BypassPhase.Failed -> {
                        onState(TunnelBackendState.Failed(phase.message))
                        done.complete(TunnelBackendState.Failed(phase.message))
                    }
                    is BypassPhase.Stopped -> {
                        if (!done.isCompleted) {
                            onState(TunnelBackendState.Stopped)
                            done.complete(TunnelBackendState.Stopped)
                        }
                    }
                    else -> Unit
                }
            }
            done.await()
        }
    }

    override fun stop() {
        session.stop(keepTun = false)
    }

    fun stopKeepingTun() {
        session.stop(keepTun = true)
    }

    /** Leave libclient in the VK call; TUN is released for Direct. */
    fun parkCall() {
        session.parkCall()
    }

    val isCallParked: Boolean
        get() = session.isCallParked

    companion object {
        private const val TAG = "BypassBackend"
    }
}

/** Implemented by [com.ardtt.app.core.VpnTunnelService]. */
interface TunEstablisher {
    fun establishTun(ip: String, dnsCsv: String, mtu: Int): ParcelFileDescriptor?
}

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
import com.ardtt.app.core.readConnectedWifiState
import com.ardtt.app.core.shouldUseTurnTcp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope

/**
 * Path B — RAW over TURN via qWDTT/SpaceNeuroX go_client (libclient.so):
 * dial (vkcalls) → TURN TCP (UDP is not assumed from Wi‑Fi) → WRAP → VPS -listen-raw.
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
        val turnTcp = shouldUseTurnTcp(
            readConnectedWifiState(service, requireBackground = false).connected,
        )
        Log.i(TAG, "TURN transport=${if (turnTcp) "tcp" else "udp"}")
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
                    turnTcp = turnTcp,
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

    fun setNetOpsAllowed(allowed: Boolean) {
        session.setNetOpsAllowed(allowed)
    }

    fun setParkedDeathHandler(handler: (() -> Unit)?) {
        session.setParkedDeathHandler(handler)
    }

    suspend fun resumeParked(
        service: VpnService,
        onState: (TunnelBackendState) -> Unit,
    ): Boolean = coroutineScope {
        onState(TunnelBackendState.Starting)
        val ok = session.resumeParked(
            scope = this,
            service = service,
            establishTun = { ip, dnsCsv, mtu ->
                (service as? TunEstablisher)?.establishTun(ip, dnsCsv, mtu)
            },
        ) { phase ->
            when (phase) {
                is BypassPhase.Running -> onState(TunnelBackendState.Running)
                is BypassPhase.Failed -> onState(TunnelBackendState.Failed(phase.message))
                is BypassPhase.Stopped -> onState(TunnelBackendState.Stopped)
                else -> Unit
            }
        }
        if (!ok) {
            onState(TunnelBackendState.Failed("Не удалось возобновить обход с прежним звонком"))
        }
        ok
    }

    companion object {
        private const val TAG = "BypassBackend"
    }
}

/** Implemented by [com.ardtt.app.core.VpnTunnelService]. */
interface TunEstablisher {
    fun establishTun(ip: String, dnsCsv: String, mtu: Int): ParcelFileDescriptor?
}

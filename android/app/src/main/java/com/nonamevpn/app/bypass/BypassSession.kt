package com.nonamevpn.app.bypass

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.BypassWorkers
import com.nonamevpn.app.core.TransportHealth
import com.nonamevpn.app.profile.VpnProfile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

data class BypassConfig(
    val profile: VpnProfile,
    val callHash: String,
    val workers: Int = BypassWorkers.DEFAULT,
    val dialPath: DialPath = DialPath.Auto,
    val silentRecreate: Boolean = false,
    val hideIp: Boolean = false,
)

sealed class BypassPhase {
    data object Idle : BypassPhase()
    data object Dialing : BypassPhase()
    data object Allocating : BypassPhase()
    data object Wrapping : BypassPhase()
    data object Running : BypassPhase()
    data class Failed(val message: String) : BypassPhase()
    data object Stopped : BypassPhase()
}

/**
 * Path B: launches go_client (vkcalls → TURN TCP → WRAP → RAW; qWDTT/SpaceNeuroX lineage),
 * establishes VpnService TUN from RAWCONF, sends FD via [TunFdBridge].
 */
class BypassSession {
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var go: BypassGoProcess? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var keepTunOnStop = false
    /** Keep libclient after TUN close so the VK call stays allocated. */
    @Volatile private var keepProcessOnCleanup = false
    @Volatile var phase: BypassPhase = BypassPhase.Idle
        private set

    fun start(
        scope: CoroutineScope,
        service: VpnService,
        config: BypassConfig,
        establishTun: (ip: String, dnsCsv: String, mtu: Int) -> ParcelFileDescriptor?,
        onPhase: (BypassPhase) -> Unit,
    ) {
        keepTunOnStop = false
        keepProcessOnCleanup = false
        stop()
        running.set(true)
        TransportHealth.noteBackendStarted()
        job = scope.launch {
            try {
                setPhase(BypassPhase.Dialing, onPhase)
                val sockName = TunFdBridge.newSocketName()
                val process = BypassGoProcess(service.applicationContext)
                go = process
                if (!process.binaryExists()) {
                    setPhase(
                        BypassPhase.Failed("Нет libclient.so — соберите scripts/build-bypass-client.sh"),
                        onPhase,
                    )
                    return@launch
                }

                val rawReady = CompletableDeferred<RawConf>()
                val fatal = CompletableDeferred<String>()

                process.start(
                    scope = this,
                    args = BypassGoArgs(
                        peer = config.profile.bypass.peer,
                        callHash = config.callHash,
                        password = config.profile.bypass.password,
                        deviceId = config.profile.deviceId,
                        workers = config.workers,
                        dialPath = config.dialPath,
                        tunSockName = sockName,
                    ),
                    onRawConf = { conf ->
                        if (!rawReady.isCompleted) rawReady.complete(conf)
                    },
                    onLog = { line ->
                        TransportHealth.onLogLine(line)
                        AppLog.i("go_client", line.take(300))
                        when {
                            line.contains("[VKCalls]") || line.contains("[VK Auth]") ->
                                Log.i(TAG, line)
                            line.contains("TURN") || line.contains("RAW") || line.contains("ПРЯМОЙ") ->
                                Log.i(TAG, line)
                        }
                    },
                    onFatal = { msg ->
                        AppLog.e(TAG, msg)
                        if (!fatal.isCompleted) fatal.complete(msg)
                    },
                )

                setPhase(BypassPhase.Allocating, onPhase)
                AppLog.i(TAG, "waiting RAWCONF…")
                val conf = select {
                    rawReady.onAwait { it }
                    fatal.onAwait { throw IllegalStateException(it) }
                }

                setPhase(BypassPhase.Wrapping, onPhase)
                AppLog.i(TAG, "RAWCONF ip=${conf.ip} dns=${conf.dnsCsv} mtu=${conf.mtu}")
                Log.i(TAG, "RAWCONF ip=${conf.ip} dns=${conf.dnsCsv} mtu=${conf.mtu}")
                val pfd = establishTun(conf.ip, conf.dnsCsv, conf.mtu)
                if (pfd == null) {
                    setPhase(BypassPhase.Failed("Не удалось создать TUN после RAWCONF"), onPhase)
                    return@launch
                }
                tun = pfd
                TunFdBridge.sendOnce(sockName, pfd)

                setPhase(BypassPhase.Running, onPhase)
                while (isActive && running.get() && process.isAlive) {
                    delay(5_000)
                }
                if (running.get() && !process.isAlive) {
                    val msg = process.lastError ?: "Процесс обхода завершился"
                    setPhase(BypassPhase.Failed(msg), onPhase)
                    return@launch
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "bypass session error", t)
                setPhase(BypassPhase.Failed(t.message ?: "bypass error"), onPhase)
            } finally {
                cleanup(keepTunOnStop)
                if (phase !is BypassPhase.Failed) {
                    setPhase(BypassPhase.Stopped, onPhase)
                }
                running.set(false)
            }
        }
    }

    fun stop(keepTun: Boolean = false) {
        // Set the flag before cancel so the coroutine finally does not close TUN.
        keepProcessOnCleanup = false
        keepTunOnStop = keepTun
        running.set(false)
        job?.cancel()
        job = null
        cleanup(keepTun)
        phase = BypassPhase.Stopped
    }

    /**
     * Close the VPN TUN but leave [libclient.so] in the VK call / TURN.
     * Direct can own the VpnService; LTE return redials the same hash
     * while the call is still live.
     */
    fun parkCall() {
        keepProcessOnCleanup = true
        keepTunOnStop = false
        running.set(false)
        TransportHealth.noteBackendStopped()
        runCatching { tun?.close() }
        tun = null
        job?.cancel()
        job = null
        phase = BypassPhase.Stopped
    }

    val isCallParked: Boolean
        get() = keepProcessOnCleanup && go?.isAlive == true

    private fun cleanup(keepTun: Boolean = false) {
        if (!keepProcessOnCleanup) {
            go?.stop()
            go = null
            TransportHealth.noteBackendStopped()
        }
        if (!keepTun) {
            runCatching { tun?.close() }
        }
        tun = null
    }

    private fun setPhase(p: BypassPhase, onPhase: (BypassPhase) -> Unit) {
        phase = p
        onPhase(p)
    }

    companion object {
        private const val TAG = "BypassSession"
    }
}

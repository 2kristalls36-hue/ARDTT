package com.nonamevpn.app.bypass

import android.util.Log
import com.nonamevpn.app.profile.VpnProfile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BypassConfig(
    val profile: VpnProfile,
    val callHash: String,
    val workers: Int = 3,
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
 * Path B session: dial → TURN TCP workers → WRAP AEAD ↔ RAW peer.
 * Tun FD I/O and TURN ChannelData land with native go_client; this class
 * owns lifecycle, WRAP key, and phase reporting for VpnTunnelService.
 */
class BypassSession(
    private val dialer: VkDialer = AutoVkDialer(),
) {
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    @Volatile var phase: BypassPhase = BypassPhase.Idle
        private set
    @Volatile var wrapKey: ByteArray? = null
        private set
    @Volatile var lastCreds: TurnCredentials? = null
        private set

    fun start(scope: CoroutineScope, config: BypassConfig, onPhase: (BypassPhase) -> Unit) {
        stop()
        running.set(true)
        job = scope.launch {
            try {
                setPhase(BypassPhase.Dialing, onPhase)
                val dial = dialer.obtainTurn(config.callHash, config.dialPath)
                when (dial) {
                    is DialResult.NeedHash -> {
                        setPhase(BypassPhase.Failed(dial.message), onPhase)
                        return@launch
                    }
                    is DialResult.NeedVkLogin -> {
                        setPhase(BypassPhase.Failed(dial.message), onPhase)
                        return@launch
                    }
                    is DialResult.Failed -> {
                        if (config.silentRecreate) {
                            Log.w(TAG, "dial failed, silent recreate not yet implemented: ${dial.message}")
                        }
                        setPhase(BypassPhase.Failed(dial.message), onPhase)
                        return@launch
                    }
                    is DialResult.Ok -> lastCreds = dial.creds
                }

                setPhase(BypassPhase.Allocating, onPhase)
                // TURN Allocate over TCP × workers — native next.
                delay(50)

                setPhase(BypassPhase.Wrapping, onPhase)
                wrapKey = WrapCrypto.deriveKey(config.profile.bypass.password)
                Log.i(
                    TAG,
                    "WRAP key ready peer=${config.profile.bypass.peer} workers=${config.workers} transport=tcp mode=raw",
                )

                setPhase(BypassPhase.Running, onPhase)
                // Keep session alive until stop; packet pump arrives with native.
                while (isActive && running.get()) {
                    delay(15_000)
                    Log.d(TAG, "bypass heartbeat peer=${config.profile.bypass.peer}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "bypass session error", t)
                setPhase(BypassPhase.Failed(t.message ?: "bypass error"), onPhase)
            } finally {
                if (phase !is BypassPhase.Failed) {
                    setPhase(BypassPhase.Stopped, onPhase)
                }
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
        wrapKey = null
        lastCreds = null
        phase = BypassPhase.Stopped
    }

    private fun setPhase(p: BypassPhase, onPhase: (BypassPhase) -> Unit) {
        phase = p
        onPhase(p)
    }

    companion object {
        private const val TAG = "BypassSession"
    }
}

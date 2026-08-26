package com.nonamevpn.app.tunnel

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.profile.VpnProfile

/** Shared session parameters for Path A / Path B backends. */
data class TunnelSessionConfig(
    val path: VpnPath,
    val profile: VpnProfile?,
    val tunAddress: String,
    val hideIp: Boolean,
    val callHash: String?,
    val workers: Int,
    val silentRecreate: Boolean,
    val dialPathName: String,
)

sealed class TunnelBackendState {
    data object Starting : TunnelBackendState()
    data object Running : TunnelBackendState()
    data class Failed(val message: String) : TunnelBackendState()
    data object Stopped : TunnelBackendState()
}

interface TunnelBackend {
    val path: VpnPath
    suspend fun start(
        service: VpnService,
        /** Null for Path B (TUN after RAWCONF). Non-null for Path A. */
        tun: ParcelFileDescriptor?,
        config: TunnelSessionConfig,
        onState: (TunnelBackendState) -> Unit,
    )
    fun stop()
}

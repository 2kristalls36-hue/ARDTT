package com.ardtt.app.core

import java.security.MessageDigest

/**
 * Handshake, worker count and leftover UID RX are not leftover proof.
 * The current attempt must produce protocol-ready exchange or a verified probe.
 */
data class PathConfirmObservation(
    val capturedSessionEpoch: Long,
    val capturedTransportEpoch: Long,
    val capturedNetworkKey: NetworkKey?,
    val eventSessionEpoch: Long,
    val eventTransportEpoch: Long,
    val eventNetworkKey: NetworkKey?,
    val tunWriteOkDelta: Long = 0L,
    val tunWriteErrDelta: Long = 0L,
    val usefulRxDelta: Long = 0L,
    val handshakeGrew: Boolean = false,
    val probeSucceeded: Boolean = false,
)

sealed class PathConfirmResult {
    data object Confirmed : PathConfirmResult()
    data class Timeout(val stage: String) : PathConfirmResult()
    data object Cancelled : PathConfirmResult()
    data class Unsupported(val reason: String = "no-in-tunnel-probe") : PathConfirmResult()
    data object BindFailure : PathConfirmResult()
    data class InternalError(val reason: String) : PathConfirmResult()
    data object StaleAttempt : PathConfirmResult()
    data object NetworkLost : PathConfirmResult()
    data class PeerRefused(val stage: String) : PathConfirmResult()
    data class DnsFailure(val stage: String) : PathConfirmResult()
    data class TlsFailure(val stage: String) : PathConfirmResult()

    val isConfirmed: Boolean get() = this is Confirmed
    val isNegativeDirectEvidence: Boolean get() = this is Timeout || this is PeerRefused
}

object PathConfirm {
    fun epochsMatch(obs: PathConfirmObservation): Boolean {
        if (obs.eventSessionEpoch != obs.capturedSessionEpoch) return false
        if (obs.eventTransportEpoch != obs.capturedTransportEpoch) return false
        if (obs.capturedNetworkKey != null &&
            obs.eventNetworkKey != null &&
            obs.capturedNetworkKey != obs.eventNetworkKey
        ) {
            return false
        }
        return true
    }

    fun looksConfirmed(obs: PathConfirmObservation): Boolean {
        if (!epochsMatch(obs)) return false
        if (obs.tunWriteErrDelta > 0L && obs.tunWriteOkDelta <= 0L) return false
        return obs.handshakeGrew || obs.usefulRxDelta > 0L || obs.probeSucceeded
    }

    fun identityToken(hash: String?): String {
        if (hash.isNullOrBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hash.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { b -> "%02x".format(b) }
    }
}

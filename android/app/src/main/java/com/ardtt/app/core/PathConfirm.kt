package com.ardtt.app.core

import java.security.MessageDigest

/**
 * Handshake, worker count and leftover UID RX are not path confirmation.
 * The current attempt must produce an expected reply through this TUN.
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

object PathConfirm {
    fun looksConfirmed(obs: PathConfirmObservation): Boolean {
        if (obs.eventSessionEpoch != obs.capturedSessionEpoch) return false
        if (obs.eventTransportEpoch != obs.capturedTransportEpoch) return false
        if (obs.capturedNetworkKey != null &&
            obs.eventNetworkKey != null &&
            obs.capturedNetworkKey != obs.eventNetworkKey
        ) {
            return false
        }
        if (obs.tunWriteErrDelta > 0L && obs.tunWriteOkDelta <= 0L) return false
        if (!obs.probeSucceeded) return false
        return true
    }

    fun identityToken(hash: String?): String {
        if (hash.isNullOrBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hash.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { b -> "%02x".format(b) }
    }
}

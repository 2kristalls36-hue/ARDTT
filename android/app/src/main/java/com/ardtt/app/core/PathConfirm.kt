package com.ardtt.app.core

import java.security.MessageDigest

enum class PathConfirmSource {
    DirectAwg,
    BypassTun,
    UidFallback,
    Sysfs,
    Unknown,
}

enum class PathConfirmVerdict {
    Stale,
    WriteFailed,
    NotReady,
    BackendRunning,
    ProtocolReady,
    PathConfirmed,
}

/**
 * Handshake, worker count and leftover UID RX are not leftover proof.
 * PathConfirmed requires useful delivery on this backend/generation.
 */
data class PathConfirmObservation(
    val capturedSessionEpoch: Long,
    val capturedTransportEpoch: Long,
    val capturedNetworkKey: NetworkKey?,
    val eventSessionEpoch: Long,
    val eventTransportEpoch: Long,
    val eventNetworkKey: NetworkKey?,
    val capturedCallEpoch: Long = 0L,
    val eventCallEpoch: Long = 0L,
    val capturedTunGen: Long = -1L,
    val eventTunGen: Long = -1L,
    val capturedBackendId: Long = -1L,
    val eventBackendId: Long = -1L,
    val capturedProcessId: Long = -1L,
    val eventProcessId: Long = -1L,
    val capturedOperationId: Long = -1L,
    val eventOperationId: Long = -1L,
    val requireCallEpoch: Boolean = true,
    val tunWriteOkDelta: Long = 0L,
    val tunWriteErrDelta: Long = 0L,
    val usefulRxDelta: Long = 0L,
    val handshakeGrew: Boolean = false,
    val probeSucceeded: Boolean = false,
    val workersPresent: Boolean = false,
    val source: PathConfirmSource = PathConfirmSource.Unknown,
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
    data object ProtocolReady : PathConfirmResult()

    val isConfirmed: Boolean get() = this is Confirmed
    val keepsDirect: Boolean get() = this is Confirmed || this is ProtocolReady
    val isNegativeDirectEvidence: Boolean get() = this is Timeout || this is PeerRefused
}

object PathConfirm {
    fun epochsMatch(obs: PathConfirmObservation): Boolean {
        if (obs.eventSessionEpoch != obs.capturedSessionEpoch) return false
        if (obs.eventTransportEpoch != obs.capturedTransportEpoch) return false
        if (obs.requireCallEpoch &&
            obs.capturedCallEpoch != 0L &&
            obs.eventCallEpoch != 0L &&
            obs.capturedCallEpoch != obs.eventCallEpoch
        ) {
            return false
        }
        if (obs.capturedNetworkKey != null &&
            obs.eventNetworkKey != null &&
            obs.capturedNetworkKey != obs.eventNetworkKey
        ) {
            return false
        }
        if (obs.capturedProcessId >= 0L &&
            obs.eventProcessId >= 0L &&
            obs.capturedProcessId != obs.eventProcessId
        ) {
            return false
        }
        if (obs.capturedOperationId >= 0L &&
            obs.eventOperationId >= 0L &&
            obs.capturedOperationId != obs.eventOperationId
        ) {
            return false
        }
        // Handle alone is not operation identity — do not Stale solely on handle change.
        // Unknown tunGen (-1) is not comparable; first known gen of this operation is adopted.
        if (obs.capturedTunGen >= 0L &&
            obs.eventTunGen >= 0L &&
            obs.capturedTunGen != obs.eventTunGen
        ) {
            return false
        }
        return true
    }

    fun sourceCountsAsPath(source: PathConfirmSource): Boolean = when (source) {
        PathConfirmSource.DirectAwg,
        PathConfirmSource.BypassTun,
        -> true
        PathConfirmSource.UidFallback,
        PathConfirmSource.Sysfs,
        PathConfirmSource.Unknown,
        -> false
    }

    fun verdict(obs: PathConfirmObservation): PathConfirmVerdict {
        if (!epochsMatch(obs)) return PathConfirmVerdict.Stale
        if (obs.tunWriteErrDelta > 0L && obs.tunWriteOkDelta <= 0L) {
            return PathConfirmVerdict.WriteFailed
        }
        val useful = obs.usefulRxDelta > 0L && sourceCountsAsPath(obs.source)
        val tunWrite = obs.tunWriteOkDelta > 0L &&
            (obs.eventTunGen < 0L || obs.capturedTunGen < 0L || obs.capturedTunGen == obs.eventTunGen)
        if (useful || tunWrite || (obs.probeSucceeded && sourceCountsAsPath(obs.source))) {
            return PathConfirmVerdict.PathConfirmed
        }
        if (obs.handshakeGrew) return PathConfirmVerdict.ProtocolReady
        if (obs.workersPresent) return PathConfirmVerdict.BackendRunning
        return PathConfirmVerdict.NotReady
    }

    fun looksConfirmed(obs: PathConfirmObservation): Boolean =
        verdict(obs) == PathConfirmVerdict.PathConfirmed

    fun protocolReady(obs: PathConfirmObservation): Boolean {
        val v = verdict(obs)
        return v == PathConfirmVerdict.ProtocolReady || v == PathConfirmVerdict.PathConfirmed
    }

    fun backendRunning(obs: PathConfirmObservation): Boolean {
        val v = verdict(obs)
        return v == PathConfirmVerdict.BackendRunning ||
            v == PathConfirmVerdict.ProtocolReady ||
            v == PathConfirmVerdict.PathConfirmed
    }

    fun directMayConnect(verdict: PathConfirmVerdict): Boolean =
        verdict == PathConfirmVerdict.PathConfirmed ||
            verdict == PathConfirmVerdict.ProtocolReady

    fun bypassMayConnect(verdict: PathConfirmVerdict): Boolean =
        verdict == PathConfirmVerdict.PathConfirmed ||
            verdict == PathConfirmVerdict.ProtocolReady ||
            verdict == PathConfirmVerdict.BackendRunning

    /**
     * Handshake grew for this operation. [newBackend] is only true when the
     * operation identity says this is a fresh backend instance with its own
     * baseline — never treat a foreign leftover handshake as ready.
     */
    fun handshakeGrew(baselineSec: Long, currentSec: Long, newBackend: Boolean): Boolean {
        if (currentSec <= 0L) return false
        if (newBackend) return true
        return currentSec > baselineSec
    }

    fun assembleDirect(
        capturedSessionEpoch: Long,
        capturedTransportEpoch: Long,
        capturedNetworkKey: NetworkKey?,
        eventSessionEpoch: Long,
        eventTransportEpoch: Long,
        eventNetworkKey: NetworkKey?,
        capturedCallEpoch: Long,
        eventCallEpoch: Long,
        capturedHandle: Long,
        eventHandle: Long,
        handshakeBaselineSec: Long,
        handshakeNowSec: Long,
        rxBaseline: Long,
        rxNow: Long,
        source: PathConfirmSource,
        tunWriteOkDelta: Long = 0L,
        tunWriteErrDelta: Long = 0L,
        capturedOperationId: Long = -1L,
        eventOperationId: Long = -1L,
        requireCallEpoch: Boolean = false,
    ): PathConfirmObservation {
        val sameOperation = capturedOperationId >= 0L &&
            eventOperationId >= 0L &&
            capturedOperationId == eventOperationId
        // Numeric handle reuse is not identity. Only treat handle change as a new
        // backend when operation ids are unavailable.
        val newBackend = if (sameOperation) {
            false
        } else if (capturedOperationId >= 0L || eventOperationId >= 0L) {
            // Different / missing operation id with a known handle: not automatic ready.
            false
        } else {
            capturedHandle >= 0L &&
                eventHandle >= 0L &&
                capturedHandle != eventHandle
        }
        val rxDelta = if (sourceCountsAsPath(source)) {
            (rxNow - rxBaseline).coerceAtLeast(0L)
        } else {
            0L
        }
        return PathConfirmObservation(
            capturedSessionEpoch = capturedSessionEpoch,
            capturedTransportEpoch = capturedTransportEpoch,
            capturedNetworkKey = capturedNetworkKey,
            eventSessionEpoch = eventSessionEpoch,
            eventTransportEpoch = eventTransportEpoch,
            eventNetworkKey = eventNetworkKey,
            capturedCallEpoch = capturedCallEpoch,
            eventCallEpoch = eventCallEpoch,
            capturedBackendId = capturedHandle,
            eventBackendId = eventHandle,
            capturedOperationId = capturedOperationId,
            eventOperationId = eventOperationId,
            requireCallEpoch = requireCallEpoch,
            tunWriteOkDelta = tunWriteOkDelta,
            tunWriteErrDelta = tunWriteErrDelta,
            usefulRxDelta = rxDelta,
            handshakeGrew = handshakeGrew(handshakeBaselineSec, handshakeNowSec, newBackend),
            probeSucceeded = false,
            workersPresent = false,
            source = source,
        )
    }

    fun assembleBypass(
        capturedSessionEpoch: Long,
        capturedTransportEpoch: Long,
        capturedNetworkKey: NetworkKey?,
        eventSessionEpoch: Long,
        eventTransportEpoch: Long,
        eventNetworkKey: NetworkKey?,
        capturedCallEpoch: Long,
        eventCallEpoch: Long,
        capturedTunGen: Long,
        eventTunGen: Long,
        tunWriteOkDelta: Long,
        tunWriteErrDelta: Long,
        usefulRxDelta: Long,
        workersPresent: Boolean,
        capturedProcessId: Long = -1L,
        eventProcessId: Long = -1L,
        capturedOperationId: Long = -1L,
        eventOperationId: Long = -1L,
    ): PathConfirmObservation = PathConfirmObservation(
        capturedSessionEpoch = capturedSessionEpoch,
        capturedTransportEpoch = capturedTransportEpoch,
        capturedNetworkKey = capturedNetworkKey,
        eventSessionEpoch = eventSessionEpoch,
        eventTransportEpoch = eventTransportEpoch,
        eventNetworkKey = eventNetworkKey,
        capturedCallEpoch = capturedCallEpoch,
        eventCallEpoch = eventCallEpoch,
        capturedTunGen = capturedTunGen,
        eventTunGen = eventTunGen,
        capturedProcessId = capturedProcessId,
        eventProcessId = eventProcessId,
        capturedOperationId = capturedOperationId,
        eventOperationId = eventOperationId,
        requireCallEpoch = true,
        tunWriteOkDelta = tunWriteOkDelta,
        tunWriteErrDelta = tunWriteErrDelta,
        usefulRxDelta = usefulRxDelta,
        handshakeGrew = false,
        probeSucceeded = false,
        workersPresent = workersPresent,
        source = PathConfirmSource.BypassTun,
    )

    fun identityToken(hash: String?): String {
        if (hash.isNullOrBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hash.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { b -> "%02x".format(b) }
    }

    /** Stable fingerprint of Direct transport parameters (no secrets logged). */
    fun directConfigRevision(profile: com.ardtt.app.profile.VpnProfile?): String {
        if (profile == null) return ""
        val d = profile.direct
        val material = buildString {
            append(profile.name)
            append('|')
            append(d.endpoint)
            append('|')
            append(d.peerPublicKey)
            append('|')
            append(d.privateKey)
            append('|')
            append(d.address)
            append('|')
            append(d.dns.joinToString(","))
            append('|')
            append(d.mtu)
            append('|')
            append(d.awg.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
            append('|')
            append(profile.bypass.peer)
            append('|')
            append(profile.bypass.password)
            append('|')
            append(profile.provisionPort)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { b -> "%02x".format(b) }
    }
}

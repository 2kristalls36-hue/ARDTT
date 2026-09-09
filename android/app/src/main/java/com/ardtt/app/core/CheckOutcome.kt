package com.ardtt.app.core

/**
 * Result of one diagnostic check. Cancelled / not-run must never become `false`.
 */
enum class CheckOutcome {
    Success,
    Timeout,
    NetworkLost,
    Suspended,
    DnsFailure,
    TransportFailure,
    AuthFailure,
    TlsFailure,
    Refused,
    BindFailure,
    NotRun,
    Cancelled,
    ;

    val isSuccess: Boolean get() = this == Success
    val isFailure: Boolean get() = when (this) {
        Timeout, NetworkLost, Suspended, DnsFailure, TransportFailure,
        AuthFailure, TlsFailure, Refused, BindFailure,
        -> true
        Success, NotRun, Cancelled -> false
    }
    val ran: Boolean get() = this != NotRun && this != Cancelled
}

/** Ordinary-target timeout/refused — not TLS cert, bind, cancel, or auth. */
fun CheckOutcome.countsAsOrdinaryBlock(): Boolean = when (this) {
    CheckOutcome.Timeout,
    CheckOutcome.NetworkLost,
    CheckOutcome.Refused,
    CheckOutcome.TransportFailure,
    CheckOutcome.DnsFailure,
    CheckOutcome.Suspended,
    -> true
    else -> false
}

fun CheckOutcome.toProbeFlag(): Boolean? = when {
    isSuccess -> true
    this == CheckOutcome.NotRun ||
        this == CheckOutcome.Cancelled ||
        this == CheckOutcome.BindFailure -> null
    else -> false
}

enum class RestrictionHint {
    Unknown,
    None,
    Suspected,
    Confirmed,
}

fun CheckOutcome.asLegacyOk(): Boolean = isSuccess

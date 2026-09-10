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

fun CheckOutcome.invalidatesRestrictionSeries(): Boolean = when (this) {
    CheckOutcome.NetworkLost,
    CheckOutcome.Suspended,
    -> true
    else -> false
}

/** Ordinary-target timeout/refused — not TLS, bind, cancel, lost radio, or auth. */
fun CheckOutcome.countsAsOrdinaryBlock(): Boolean = when (this) {
    CheckOutcome.Timeout,
    CheckOutcome.Refused,
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

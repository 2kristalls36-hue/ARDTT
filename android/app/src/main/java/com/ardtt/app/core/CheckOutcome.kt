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
    NotRun,
    Cancelled,
    ;

    val isSuccess: Boolean get() = this == Success
    val isFailure: Boolean get() = when (this) {
        Timeout, NetworkLost, Suspended, DnsFailure, TransportFailure, AuthFailure -> true
        Success, NotRun, Cancelled -> false
    }
    val ran: Boolean get() = this != NotRun && this != Cancelled
}

enum class RestrictionHint {
    Unknown,
    None,
    Suspected,
    Confirmed,
}

fun CheckOutcome.asLegacyOk(): Boolean = isSuccess

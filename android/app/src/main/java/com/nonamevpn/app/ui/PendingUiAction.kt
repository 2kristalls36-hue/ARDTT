package com.nonamevpn.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot UI navigation from Quick Settings / in-app chips
 * to the «Код звонка» card.
 */
object PendingUiAction {
    private val _openCallHashSettings = MutableStateFlow(false)
    val openCallHashSettings: StateFlow<Boolean> = _openCallHashSettings.asStateFlow()

    fun requestCallHashSettings() {
        _openCallHashSettings.value = true
    }

    fun consumeCallHashSettings(): Boolean {
        if (!_openCallHashSettings.value) return false
        _openCallHashSettings.value = false
        return true
    }
}

/** Bypass chip / QS tile: without a call hash the control looks inactive. */
internal fun callHashMissing(hasCallHash: Boolean): Boolean = !hasCallHash

/** QS tile is inert (opens call-hash settings) only when disconnected and hash is missing. */
internal fun qsTileOpensCallHashSettings(hasCallHash: Boolean, running: Boolean): Boolean =
    !hasCallHash && !running

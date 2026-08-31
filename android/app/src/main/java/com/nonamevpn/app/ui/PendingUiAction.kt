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

    private val _openDeployServerId = MutableStateFlow<String?>(null)
    val openDeployServerId: StateFlow<String?> = _openDeployServerId.asStateFlow()

    fun requestCallHashSettings() {
        _openCallHashSettings.value = true
    }

    fun consumeCallHashSettings(): Boolean {
        if (!_openCallHashSettings.value) return false
        _openCallHashSettings.value = false
        return true
    }

    fun requestOpenDeploy(serverId: String) {
        val id = serverId.trim()
        if (id.isEmpty()) return
        _openDeployServerId.value = id
    }

    fun consumeOpenDeploy(): String? {
        val id = _openDeployServerId.value ?: return null
        _openDeployServerId.value = null
        return id
    }
}

/** Bypass chip / QS tile: without a call hash the control looks inactive. */
internal fun callHashMissing(hasCallHash: Boolean): Boolean = !hasCallHash

/** QS tile is inert (opens call-hash settings) only when disconnected and hash is missing. */
internal fun qsTileOpensCallHashSettings(hasCallHash: Boolean, running: Boolean): Boolean =
    !hasCallHash && !running

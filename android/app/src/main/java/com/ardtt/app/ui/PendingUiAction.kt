package com.ardtt.app.ui

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

    private val _openUpdateDownload = MutableStateFlow(false)
    val openUpdateDownload: StateFlow<Boolean> = _openUpdateDownload.asStateFlow()

    private val _openAppearanceSettings = MutableStateFlow(false)
    val openAppearanceSettings: StateFlow<Boolean> = _openAppearanceSettings.asStateFlow()

    private val _openProfiles = MutableStateFlow(false)
    val openProfiles: StateFlow<Boolean> = _openProfiles.asStateFlow()

    private val _openServers = MutableStateFlow(false)
    val openServers: StateFlow<Boolean> = _openServers.asStateFlow()

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

    fun requestOpenUpdateDownload() {
        _openUpdateDownload.value = true
    }

    fun consumeOpenUpdateDownload(): Boolean {
        if (!_openUpdateDownload.value) return false
        _openUpdateDownload.value = false
        return true
    }

    fun requestOpenAppearanceSettings() {
        _openAppearanceSettings.value = true
    }

    fun consumeOpenAppearanceSettings(): Boolean {
        if (!_openAppearanceSettings.value) return false
        _openAppearanceSettings.value = false
        return true
    }

    fun requestOpenProfiles() {
        _openProfiles.value = true
    }

    fun consumeOpenProfiles(): Boolean {
        if (!_openProfiles.value) return false
        _openProfiles.value = false
        return true
    }

    fun requestOpenServers() {
        _openServers.value = true
    }

    fun consumeOpenServers(): Boolean {
        if (!_openServers.value) return false
        _openServers.value = false
        return true
    }
}

/** Bypass chip / QS tile: without a call hash the control looks inactive. */
internal fun callHashMissing(hasCallHash: Boolean): Boolean = !hasCallHash

/** QS tile is inert (opens call-hash settings) only when disconnected and hash is missing. */
internal fun qsTileOpensCallHashSettings(hasCallHash: Boolean, running: Boolean): Boolean =
    !hasCallHash && !running

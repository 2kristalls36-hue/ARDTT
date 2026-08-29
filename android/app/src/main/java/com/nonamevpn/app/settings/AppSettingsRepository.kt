package com.nonamevpn.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nonamevpn.app.core.sanitizeTrustedWifiSsid
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("nvpn_settings")

class AppSettingsRepository(private val context: Context) {
    private val adminUnlocked = booleanPreferencesKey("admin_unlocked")
    private val adminPinHash = stringPreferencesKey("admin_pin_hash")
    private val hideIp = booleanPreferencesKey("hide_ip")
    private val profileName = stringPreferencesKey("profile_name")
    private val silentRecreate = booleanPreferencesKey("silent_recreate")
    private val dialPath = stringPreferencesKey("dial_path")
    private val testingMode = booleanPreferencesKey("testing_mode")
    private val testingAgreementVersion = intPreferencesKey("testing_agreement_version")
    private val pathMode = stringPreferencesKey("conn_path_mode")
    private val trustedWifiEnabled = booleanPreferencesKey("trusted_wifi_enabled")
    private val trustedWifiSsids = stringPreferencesKey("trusted_wifi_ssids")
    private val vpnNotificationVisible = booleanPreferencesKey("vpn_notification_visible")
    private val excludedApps = stringPreferencesKey("excluded_apps")
    private val excludedHosts = stringPreferencesKey("excluded_hosts")
    private val appsWhitelistMode = booleanPreferencesKey("apps_whitelist_mode")
    private val themeMode = stringPreferencesKey("theme_mode")
    private val themePalette = stringPreferencesKey("theme_palette")
    private val dynamicColor = booleanPreferencesKey("is_dynamic_color")

    val isAdminUnlocked: Flow<Boolean> = context.dataStore.data.map { it[adminUnlocked] == true }
    val testingModeEnabled: Flow<Boolean> = context.dataStore.data.map { it[testingMode] == true }
    val testingAgreementVersionFlow: Flow<Int> =
        context.dataStore.data.map { it[testingAgreementVersion] ?: 0 }
    val hideIpEnabled: Flow<Boolean> = context.dataStore.data.map { it[hideIp] == true }
    val hasAdminPin: Flow<Boolean> = context.dataStore.data.map { !it[adminPinHash].isNullOrBlank() }
    val currentProfileName: Flow<String> = context.dataStore.data.map { it[profileName] ?: "" }
    val silentRecreateEnabled: Flow<Boolean> = context.dataStore.data.map { it[silentRecreate] == true }
    /** `auto` | `vkcalls` | `legacy` — Path B TURN dial. */
    val dialPathName: Flow<String> = context.dataStore.data.map {
        normalizeDialPath(it[dialPath])
    }
    /** `auto` | `direct` | `bypass` — tunnel path override. */
    val pathModeName: Flow<String> = context.dataStore.data.map {
        normalizePathMode(it[pathMode])
    }
    val trustedWifiEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[trustedWifiEnabled] == true }
    /** Newline-separated SSIDs. */
    val trustedWifiSsidsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        parseSsidSet(prefs[trustedWifiSsids])
    }
    /** Default true — show VPN status in notification shade. */
    val vpnNotificationVisibleFlow: Flow<Boolean> =
        context.dataStore.data.map { it[vpnNotificationVisible] != false }
    /** Package names that bypass the VPN (disallowed applications). */
    val excludedAppsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        parseLineSet(prefs[excludedApps])
    }
    /** Hostnames / IPv4 that should leave the tunnel (API 33+ excludeRoute). */
    val excludedHostsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        parseLineSet(prefs[excludedHosts]).map { normalizeHost(it) }.filter { it.isNotBlank() }.toSet()
    }
    /** true = БС (только выбранные через VPN), false = ЧС (выбранные мимо VPN). */
    val appsWhitelistModeFlow: Flow<Boolean> =
        context.dataStore.data.map { it[appsWhitelistMode] == true }
    /** `system` | `light` | `dark` */
    val themeModeFlow: Flow<String> = context.dataStore.data.map {
        normalizeThemeMode(it[themeMode])
    }
    /** `espresso` | `indigo` | `forest` */
    val themePaletteFlow: Flow<String> = context.dataStore.data.map {
        normalizeThemePalette(it[themePalette])
    }
    val dynamicColorFlow: Flow<Boolean> =
        context.dataStore.data.map { it[dynamicColor] == true }

    suspend fun setHideIp(enabled: Boolean) {
        context.dataStore.edit { it[hideIp] = enabled }
    }

    suspend fun setProfileName(name: String) {
        context.dataStore.edit { it[profileName] = name }
    }

    suspend fun setSilentRecreate(enabled: Boolean) {
        context.dataStore.edit { it[silentRecreate] = enabled }
    }

    suspend fun setDialPath(name: String) {
        context.dataStore.edit { it[dialPath] = normalizeDialPath(name) }
    }

    suspend fun setPathMode(name: String) {
        context.dataStore.edit { it[pathMode] = normalizePathMode(name) }
    }

    suspend fun setTrustedWifiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[trustedWifiEnabled] = enabled }
    }

    suspend fun setTrustedWifiSsids(ssids: Set<String>) {
        context.dataStore.edit {
            it[trustedWifiSsids] = ssids
                .map { s -> sanitizeTrustedWifiSsid(s) }
                .filter { s -> s.isNotBlank() }
                .distinct()
                .joinToString("\n")
        }
    }

    suspend fun addTrustedWifiSsid(ssid: String) {
        val clean = sanitizeTrustedWifiSsid(ssid)
        if (clean.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = parseSsidSet(prefs[trustedWifiSsids]).toMutableSet()
            current.add(clean)
            prefs[trustedWifiSsids] = current.joinToString("\n")
        }
    }

    suspend fun removeTrustedWifiSsid(ssid: String) {
        context.dataStore.edit { prefs ->
            val current = parseSsidSet(prefs[trustedWifiSsids]).toMutableSet()
            current.remove(sanitizeTrustedWifiSsid(ssid))
            prefs[trustedWifiSsids] = current.joinToString("\n")
        }
    }

    suspend fun setVpnNotificationVisible(visible: Boolean) {
        context.dataStore.edit { it[vpnNotificationVisible] = visible }
    }

    suspend fun setAppsWhitelistMode(whitelist: Boolean) {
        context.dataStore.edit { it[appsWhitelistMode] = whitelist }
    }

    suspend fun appsWhitelistModeSnapshot(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[appsWhitelistMode] == true
    }

    suspend fun setExcludedApps(packages: Set<String>) {
        context.dataStore.edit {
            it[excludedApps] = packages
                .map { p -> p.trim() }
                .filter { p -> p.isNotBlank() }
                .distinct()
                .sorted()
                .joinToString("\n")
        }
    }

    suspend fun addExcludedApp(packageName: String) {
        val clean = packageName.trim()
        if (clean.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = parseLineSet(prefs[excludedApps]).toMutableSet()
            current.add(clean)
            prefs[excludedApps] = current.sorted().joinToString("\n")
        }
    }

    suspend fun removeExcludedApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = parseLineSet(prefs[excludedApps]).toMutableSet()
            current.remove(packageName.trim())
            prefs[excludedApps] = current.sorted().joinToString("\n")
        }
    }

    suspend fun setExcludedHosts(hosts: Set<String>) {
        context.dataStore.edit {
            it[excludedHosts] = hosts
                .map { h -> normalizeHost(h) }
                .filter { h -> h.isNotBlank() }
                .distinct()
                .sorted()
                .joinToString("\n")
        }
    }

    suspend fun addExcludedHost(host: String) {
        val clean = normalizeHost(host)
        if (clean.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = parseLineSet(prefs[excludedHosts]).map { normalizeHost(it) }.toMutableSet()
            current.add(clean)
            prefs[excludedHosts] = current.filter { it.isNotBlank() }.sorted().joinToString("\n")
        }
    }

    suspend fun removeExcludedHost(host: String) {
        val clean = normalizeHost(host)
        context.dataStore.edit { prefs ->
            val current = parseLineSet(prefs[excludedHosts]).map { normalizeHost(it) }.toMutableSet()
            current.remove(clean)
            prefs[excludedHosts] = current.filter { it.isNotBlank() }.sorted().joinToString("\n")
        }
    }

    /** Snapshot for VpnService (call from IO/coroutine). */
    suspend fun trustedWifiSnapshot(): Pair<Boolean, Set<String>> {
        val prefs = context.dataStore.data.first()
        return (prefs[trustedWifiEnabled] == true) to parseSsidSet(prefs[trustedWifiSsids])
    }

    suspend fun vpnNotificationVisibleSnapshot(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[vpnNotificationVisible] != false
    }

    suspend fun excludedAppsSnapshot(): Set<String> {
        val prefs = context.dataStore.data.first()
        return parseLineSet(prefs[excludedApps])
    }

    suspend fun excludedHostsSnapshot(): Set<String> {
        val prefs = context.dataStore.data.first()
        return parseLineSet(prefs[excludedHosts]).map { normalizeHost(it) }.filter { it.isNotBlank() }.toSet()
    }

    suspend fun unlockAdmin() {
        context.dataStore.edit { it[adminUnlocked] = true }
    }

    /** @deprecated PIN removed — use [unlockAdmin]. Kept for binary compat of older calls. */
    suspend fun unlockAdmin(pin: String): Boolean {
        unlockAdmin()
        return true
    }

    suspend fun setAdminPin(pin: String) {
        // No-op: PIN flow removed in favour of long-press unlock.
        unlockAdmin()
    }

    suspend fun lockAdmin() {
        context.dataStore.edit { it[adminUnlocked] = false }
    }

    suspend fun setTestingMode(enabled: Boolean) {
        context.dataStore.edit { it[testingMode] = enabled }
    }

    suspend fun setTestingAgreementVersion(version: Int) {
        context.dataStore.edit { it[testingAgreementVersion] = version }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[themeMode] = normalizeThemeMode(mode) }
    }

    suspend fun setThemePalette(palette: String) {
        context.dataStore.edit { it[themePalette] = normalizeThemePalette(palette) }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[dynamicColor] = enabled }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun normalizeDialPath(raw: String?): String = when (raw?.lowercase()?.trim()) {
            "vkcalls" -> "vkcalls"
            "legacy" -> "legacy"
            else -> "auto"
        }

        fun normalizePathMode(raw: String?): String = when (raw?.lowercase()?.trim()) {
            "direct", "awg" -> "direct"
            "bypass", "wdtt" -> "bypass"
            else -> "auto"
        }

        fun normalizeThemeMode(raw: String?): String = when (raw?.lowercase()?.trim()) {
            "light" -> "light"
            "dark" -> "dark"
            else -> "system"
        }

        fun normalizeThemePalette(raw: String?): String = when (raw?.lowercase()?.trim()) {
            "indigo" -> "indigo"
            "forest" -> "forest"
            else -> "espresso"
        }

        fun parseSsidSet(raw: String?): Set<String> =
            parseLineSet(raw)
                .map { sanitizeTrustedWifiSsid(it) }
                .filter { it.isNotBlank() }
                .toSet()

        fun parseLineSet(raw: String?): Set<String> =
            raw.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()

        fun normalizeHost(value: String): String {
            var h = value.trim().lowercase()
            if (h.startsWith("http://")) h = h.removePrefix("http://")
            if (h.startsWith("https://")) h = h.removePrefix("https://")
            h = h.substringBefore('/').substringBefore(':').trim()
            return h
        }
    }
}

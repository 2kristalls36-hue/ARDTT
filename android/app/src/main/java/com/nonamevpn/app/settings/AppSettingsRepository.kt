package com.nonamevpn.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nonamevpn.app.core.HostExclusion
import com.nonamevpn.app.core.sanitizeTrustedWifiSsid
import com.nonamevpn.app.unlock.AlphaGate
import com.nonamevpn.app.unlock.AlphaUnlockResult
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
    private val hideTunnelQuickSettings = booleanPreferencesKey("hide_tunnel_quick_settings")
    private val unlockConnControls = booleanPreferencesKey("unlock_conn_controls")
    private val excludedApps = stringPreferencesKey("excluded_apps")
    private val excludedHosts = stringPreferencesKey("excluded_hosts")
    private val appsWhitelistMode = booleanPreferencesKey("apps_whitelist_mode")
    private val themeMode = stringPreferencesKey("theme_mode")
    private val alphaChallengeHex = stringPreferencesKey("alpha_challenge_hex")
    private val alphaUnlocked = booleanPreferencesKey("alpha_unlocked")
    private val alphaUnlockFails = intPreferencesKey("alpha_unlock_fails")
    private val alphaUnlockLockUntil = longPreferencesKey("alpha_unlock_lock_until")
    private val legacyWallpaper = stringPreferencesKey("app_wallpaper")

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
    /** Default false — keep «Параметры подключения» visible on the Tunnel tab. */
    val hideTunnelQuickSettingsFlow: Flow<Boolean> =
        context.dataStore.data.map { it[hideTunnelQuickSettings] == true }
    /** Allow path / Hide-IP changes while the tunnel is up. */
    val unlockConnControlsFlow: Flow<Boolean> =
        context.dataStore.data.map { it[unlockConnControls] == true }
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
    val alphaUnlockedFlow: Flow<Boolean> =
        context.dataStore.data.map { it[alphaUnlocked] == true }

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

    suspend fun setHideTunnelQuickSettings(hidden: Boolean) {
        context.dataStore.edit { it[hideTunnelQuickSettings] = hidden }
    }

    suspend fun setUnlockConnControls(enabled: Boolean) {
        context.dataStore.edit { it[unlockConnControls] = enabled }
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
        // No-op: PIN flow removed in favour of slider unlock.
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

    suspend fun alphaUnlockedSnapshot(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[alphaUnlocked] == true
    }

    /**
     * Device challenge is created once and never rotated. Regenerating it would
     * invalidate a code the developer already issued.
     */
    suspend fun ensureAlphaChallengeHex(): String {
        var result = ""
        context.dataStore.edit { prefs ->
            val existing = AlphaGate.normalizeChallenge(prefs[alphaChallengeHex].orEmpty())
            if (existing != null) {
                result = existing
            } else {
                val generated = AlphaGate.newChallengeHex()
                prefs[alphaChallengeHex] = generated
                result = generated
            }
        }
        return result
    }

    suspend fun tryAlphaUnlock(otp: String): AlphaUnlockResult {
        var result: AlphaUnlockResult = AlphaUnlockResult.WrongCode(fails = 0, lockMs = 0L)
        context.dataStore.edit { prefs ->
            if (prefs[alphaUnlocked] == true) {
                result = AlphaUnlockResult.Success
                return@edit
            }
            val now = System.currentTimeMillis()
            val lockUntil = prefs[alphaUnlockLockUntil] ?: 0L
            if (now < lockUntil) {
                result = AlphaUnlockResult.Locked(lockUntil - now)
                return@edit
            }
            val challenge = AlphaGate.normalizeChallenge(prefs[alphaChallengeHex].orEmpty())
                ?: AlphaGate.newChallengeHex().also { prefs[alphaChallengeHex] = it }
            if (AlphaGate.otpMatches(challenge, otp)) {
                prefs[alphaUnlocked] = true
                prefs[alphaUnlockFails] = 0
                prefs[alphaUnlockLockUntil] = 0L
                result = AlphaUnlockResult.Success
                return@edit
            }
            val fails = (prefs[alphaUnlockFails] ?: 0) + 1
            val lockMs = AlphaGate.lockMsAfterFails(fails)
            prefs[alphaUnlockFails] = fails
            if (lockMs > 0L) {
                prefs[alphaUnlockLockUntil] = now + lockMs
            }
            result = AlphaUnlockResult.WrongCode(fails = fails, lockMs = lockMs)
        }
        return result
    }

    /** Removes obsolete wallpaper preferences from older builds. */
    suspend fun clearLegacyWallpaperPreference() {
        context.dataStore.edit {
            it.remove(legacyWallpaper)
            it.remove(intPreferencesKey("tunnel_wallpaper_variant"))
        }
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

        fun normalizeHost(value: String): String = HostExclusion.normalize(value)
    }
}

package com.nonamevpn.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateSelectTest {

    @Test
    fun githubWinsWhenItIsNewer() {
        val chosen = preferredAppUpdate(
            github = info(220, AppUpdateManifestKind.DirectApk),
            vps = info(219, AppUpdateManifestKind.LegacyVps),
            installedVersionCode = 219,
        )
        requireNotNull(chosen)
        assertEquals(220, chosen.versionCode)
        assertEquals(AppUpdateManifestKind.DirectApk, chosen.manifestKind)
    }

    @Test
    fun vpsIsUsedWhenGitHubIsMissing() {
        val chosen = preferredAppUpdate(
            github = null,
            vps = info(220, AppUpdateManifestKind.LegacyVps),
            installedVersionCode = 219,
        )
        requireNotNull(chosen)
        assertEquals(220, chosen.versionCode)
        assertEquals(AppUpdateManifestKind.LegacyVps, chosen.manifestKind)
    }

    @Test
    fun vpsIsUsedWhenGitHubIsNotNewer() {
        val chosen = preferredAppUpdate(
            github = info(219, AppUpdateManifestKind.DirectApk),
            vps = info(220, AppUpdateManifestKind.LegacyVps),
            installedVersionCode = 219,
        )
        requireNotNull(chosen)
        assertEquals(220, chosen.versionCode)
        assertEquals(AppUpdateManifestKind.LegacyVps, chosen.manifestKind)
    }

    @Test
    fun vpsWinsOnlyWhenStrictlyNewerThanGitHub() {
        val chosen = preferredAppUpdate(
            github = info(220, AppUpdateManifestKind.DirectApk),
            vps = info(221, AppUpdateManifestKind.LegacyVps),
            installedVersionCode = 219,
        )
        requireNotNull(chosen)
        assertEquals(221, chosen.versionCode)
        assertEquals(AppUpdateManifestKind.LegacyVps, chosen.manifestKind)
    }

    @Test
    fun githubPreferredWhenCodesMatch() {
        val chosen = preferredAppUpdate(
            github = info(220, AppUpdateManifestKind.DirectApk),
            vps = info(220, AppUpdateManifestKind.LegacyVps),
            installedVersionCode = 219,
        )
        requireNotNull(chosen)
        assertEquals(AppUpdateManifestKind.DirectApk, chosen.manifestKind)
    }

    @Test
    fun currentInstallYieldsNoUpdate() {
        assertNull(
            preferredAppUpdate(
                github = info(219, AppUpdateManifestKind.DirectApk),
                vps = info(219, AppUpdateManifestKind.LegacyVps),
                installedVersionCode = 219,
            ),
        )
    }

    @Test
    fun bothMissingYieldsNoUpdate() {
        assertNull(preferredAppUpdate(null, null, 219))
    }

    @Test
    fun catalogErrorUsesVpsErrorWhenBothMissing() {
        val error = IllegalStateException("Сервер обновлений вернул 500")
        val thrown = appUpdateCheckFailure(github = null, vps = null, vpsError = error)
        assertEquals(error, thrown)
    }

    @Test
    fun catalogCurrentIsNotAnError() {
        assertTrue(
            appUpdateCheckFailure(
                github = info(219, AppUpdateManifestKind.DirectApk),
                vps = null,
                vpsError = IllegalStateException("down"),
            ) is NoUpdateAvailableException,
        )
    }

    private fun info(code: Int, kind: AppUpdateManifestKind) = AppUpdateInfo(
        versionCode = code,
        versionName = "0.5.$code",
        apkUrl = "https://example/$code.apk",
        sha256 = "",
        sizeBytes = 1L,
        notes = "",
        manifestKind = kind,
    )
}

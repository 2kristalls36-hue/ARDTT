package com.nonamevpn.app.update

/**
 * GitHub Releases is the primary catalog. The VPS `update.json` is the fallback
 * when Releases is unreachable or behind.
 */
fun preferredAppUpdate(
    github: AppUpdateInfo?,
    vps: AppUpdateInfo?,
    installedVersionCode: Int,
): AppUpdateInfo? {
    val githubNewer = github?.takeIf { it.versionCode > installedVersionCode }
    val vpsNewer = vps?.takeIf { it.versionCode > installedVersionCode }
    if (githubNewer == null) return vpsNewer
    if (vpsNewer == null) return githubNewer
    return if (vpsNewer.versionCode > githubNewer.versionCode) vpsNewer else githubNewer
}

fun appUpdateCheckFailure(
    github: AppUpdateInfo?,
    vps: AppUpdateInfo?,
    vpsError: Throwable?,
): Throwable {
    if (github == null && vps == null && vpsError != null) return vpsError
    return NoUpdateAvailableException()
}

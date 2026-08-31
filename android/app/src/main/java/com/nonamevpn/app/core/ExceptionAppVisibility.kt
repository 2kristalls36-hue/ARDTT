package com.nonamevpn.app.core

/**
 * Default app-exceptions list: everyday apps (including preinstalled browsers),
 * not only Play-installed packages. Chrome/Samsung Internet/Yandex Browser ship
 * with [android.content.pm.ApplicationInfo.FLAG_SYSTEM] on most phones.
 */
object ExceptionAppVisibility {
    val knownBrowsers: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome",
        "com.android.browser",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.focus",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.mini.native.beta",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.yandex.browser",
        "com.yandex.browser.beta",
        "com.yandex.browser.alpha",
        "com.huawei.browser",
        "com.mi.globalbrowser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "org.chromium.chrome",
    )

    fun isUserFacing(
        packageName: String,
        hasLauncher: Boolean,
        httpsHandlerPackages: Set<String>,
    ): Boolean =
        hasLauncher ||
            packageName in httpsHandlerPackages ||
            packageName in knownBrowsers

    /** Hidden unless «Системные приложения» is on: system package without a UI. */
    fun hideByDefault(systemPackage: Boolean, userFacing: Boolean): Boolean =
        systemPackage && !userFacing
}

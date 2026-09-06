package com.ardtt.app.ui.tunnel

import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.NetworkClass
import com.ardtt.app.core.VpnPath
import kotlin.random.Random

/**
 * One illustrated scene for the whole process (cold start).
 *
 * Field = поле, City = киберпанк-город, Refinery = НПЗ,
 * Port = порт, River = набережная, Lake = озеро, Coast = маяк, Dam = ГЭС.
 * Every user-mode tab draws this same scene — Tunnel included.
 */
enum class TunnelWallpaperScene {
    Field,
    City,
    Refinery,
    Port,
    River,
    Lake,
    Coast,
    Dam,
    ;

    companion object {
        val all: List<TunnelWallpaperScene> = entries.toList()

        fun random(): TunnelWallpaperScene = all[Random.nextInt(all.size)]
    }
}

/**
 * Time of day for the current scene.
 *
 * Day = light theme, Night = dark theme, Evening = bypass («Обход»),
 * which wins over the theme.
 */
enum class TunnelWallpaperTime {
    Day,
    Night,
    Evening,
}

data class TunnelWallpaperId(
    val scene: TunnelWallpaperScene,
    val time: TunnelWallpaperTime,
)

/**
 * Evening when the tunnel is on (or will use) Path B / RAW обход.
 * Direct-only mode never uses evening, even if a probe later looks like БС.
 */
fun wallpaperBypassActive(
    pathMode: ConnPathMode,
    activePath: VpnPath? = null,
    networkClass: NetworkClass? = null,
): Boolean = when (pathMode) {
    ConnPathMode.Bypass -> true
    ConnPathMode.Direct -> false
    ConnPathMode.Auto ->
        activePath == VpnPath.Bypass ||
            networkClass == NetworkClass.NeedBypass ||
            networkClass == NetworkClass.OpenNeedBypass
}

fun resolveTunnelWallpaperTime(
    bypass: Boolean,
    darkTheme: Boolean,
): TunnelWallpaperTime = when {
    bypass -> TunnelWallpaperTime.Evening
    darkTheme -> TunnelWallpaperTime.Night
    else -> TunnelWallpaperTime.Day
}

fun resolveTunnelWallpaper(
    scene: TunnelWallpaperScene,
    bypass: Boolean,
    darkTheme: Boolean,
): TunnelWallpaperId = TunnelWallpaperId(
    scene = scene,
    time = resolveTunnelWallpaperTime(bypass, darkTheme),
)

/**
 * Illustrated wallpaper on every tab in user mode.
 * Admin, and user-mode classic appearance, keep the gradient chrome.
 */
fun tunnelWallpaperVisible(admin: Boolean, classicAppearance: Boolean = false): Boolean =
    !admin && !classicAppearance

/** Chrome on the Tunnel tab. Same flags as [tunnelWallpaperVisible]. */
enum class TunnelSessionChrome { User, Admin }

fun tunnelSessionChrome(admin: Boolean, classicAppearance: Boolean = false): TunnelSessionChrome =
    if (tunnelWallpaperVisible(admin, classicAppearance)) {
        TunnelSessionChrome.User
    } else {
        TunnelSessionChrome.Admin
    }

/**
 * Walk through every scene before repeating.
 * A small pool skipped НПЗ for long stretches of cold starts.
 */
fun nextTunnelWallpaperScene(
    previousName: String?,
    seenNames: Set<String>,
    randomInt: (Int) -> Int = { Random.nextInt(it) },
): Pair<TunnelWallpaperScene, Set<String>> {
    val known = TunnelWallpaperScene.all.map { it.name }.toSet()
    val seen = seenNames.filter { it in known }.toSet()
    val unused = TunnelWallpaperScene.all.filter { it.name !in seen }
    val picked = if (unused.isNotEmpty()) {
        unused[randomInt(unused.size)]
    } else {
        val pool = TunnelWallpaperScene.all.filter { it.name != previousName }
            .ifEmpty { TunnelWallpaperScene.all }
        pool[randomInt(pool.size)]
    }
    val nextSeen = if (unused.isEmpty()) setOf(picked.name) else seen + picked.name
    return picked to nextSeen
}

/** One scene per app process (cold start), rotated so every scene appears. */
object TunnelWallpaperSession {
    private var sceneOrNull: TunnelWallpaperScene? = null

    val scene: TunnelWallpaperScene
        get() = sceneOrNull ?: error("TunnelWallpaperSession not initialized")

    fun initForProcess(picked: TunnelWallpaperScene? = null) {
        if (sceneOrNull != null) return
        sceneOrNull = picked ?: TunnelWallpaperScene.random()
    }

    fun currentOrPick(): TunnelWallpaperScene {
        initForProcess()
        return scene
    }

    internal fun resetForTests() {
        sceneOrNull = null
    }
}

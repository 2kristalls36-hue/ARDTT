package com.nonamevpn.app.ui.tunnel

import kotlin.random.Random

/** Illustrated Tunnel-tab scenes: field, cyberpunk city, refinery. */
enum class TunnelWallpaperScene {
    Field,
    City,
    Refinery,
    ;

    companion object {
        val all: List<TunnelWallpaperScene> = listOf(Field, City, Refinery)

        fun random(): TunnelWallpaperScene = all[Random.nextInt(all.size)]
    }
}

/** Day = light theme, night = dark theme, sunset = app whitelist (БС). */
enum class TunnelWallpaperTime {
    Day,
    Night,
    Sunset,
}

data class TunnelWallpaperId(
    val scene: TunnelWallpaperScene,
    val time: TunnelWallpaperTime,
)

fun resolveTunnelWallpaperTime(
    whitelistMode: Boolean,
    darkTheme: Boolean,
): TunnelWallpaperTime = when {
    whitelistMode -> TunnelWallpaperTime.Sunset
    darkTheme -> TunnelWallpaperTime.Night
    else -> TunnelWallpaperTime.Day
}

fun resolveTunnelWallpaper(
    scene: TunnelWallpaperScene,
    whitelistMode: Boolean,
    darkTheme: Boolean,
): TunnelWallpaperId = TunnelWallpaperId(
    scene = scene,
    time = resolveTunnelWallpaperTime(whitelistMode, darkTheme),
)

/** Wallpaper on every tab in user mode. Admin keeps the gradient backdrop. */
fun tunnelWallpaperVisible(admin: Boolean): Boolean = !admin

/**
 * Walk through Field, City, and Refinery before repeating.
 * A plain 1/3 roll skipped НПЗ for long stretches of cold starts.
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

/** One scene per app process (cold start), rotated so all three appear. */
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

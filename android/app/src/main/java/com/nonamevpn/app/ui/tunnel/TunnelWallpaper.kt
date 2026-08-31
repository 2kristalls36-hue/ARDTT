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

/** Wallpaper only on the Tunnel tab in user mode. Admin keeps the gradient backdrop. */
fun tunnelWallpaperVisible(admin: Boolean, onTunnelTab: Boolean): Boolean =
    !admin && onTunnelTab

/** One random tunnel scene per app process (cold start). */
object TunnelWallpaperSession {
    private var sceneOrNull: TunnelWallpaperScene? = null

    val scene: TunnelWallpaperScene
        get() = sceneOrNull ?: error("TunnelWallpaperSession not initialized")

    fun initForProcess() {
        if (sceneOrNull == null) {
            sceneOrNull = TunnelWallpaperScene.random()
        }
    }

    fun currentOrPick(): TunnelWallpaperScene {
        initForProcess()
        return scene
    }

    internal fun resetForTests() {
        sceneOrNull = null
    }
}

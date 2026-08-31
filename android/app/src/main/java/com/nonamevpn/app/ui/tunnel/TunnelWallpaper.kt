package com.nonamevpn.app.ui.tunnel

import androidx.annotation.DrawableRes
import com.nonamevpn.app.R
import kotlin.random.Random

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

enum class TunnelWallpaperTime {
    Day,
    Night,
    Sunset,
}

/** Illustrated tunnel tab backgrounds: field, cyberpunk city, refinery × day/night/sunset. */
enum class TunnelWallpaper(
    val scene: TunnelWallpaperScene,
    internal val time: TunnelWallpaperTime,
    @DrawableRes val drawableRes: Int,
) {
    FieldDay(TunnelWallpaperScene.Field, TunnelWallpaperTime.Day, R.drawable.bg_tunnel_field_day),
    FieldNight(TunnelWallpaperScene.Field, TunnelWallpaperTime.Night, R.drawable.bg_tunnel_field_night),
    FieldSunset(TunnelWallpaperScene.Field, TunnelWallpaperTime.Sunset, R.drawable.bg_tunnel_field_sunset),
    CityDay(TunnelWallpaperScene.City, TunnelWallpaperTime.Day, R.drawable.bg_tunnel_city_day),
    CityNight(TunnelWallpaperScene.City, TunnelWallpaperTime.Night, R.drawable.bg_tunnel_city_night),
    CitySunset(TunnelWallpaperScene.City, TunnelWallpaperTime.Sunset, R.drawable.bg_tunnel_city_sunset),
    RefineryDay(TunnelWallpaperScene.Refinery, TunnelWallpaperTime.Day, R.drawable.bg_tunnel_refinery_day),
    RefineryNight(TunnelWallpaperScene.Refinery, TunnelWallpaperTime.Night, R.drawable.bg_tunnel_refinery_night),
    RefinerySunset(TunnelWallpaperScene.Refinery, TunnelWallpaperTime.Sunset, R.drawable.bg_tunnel_refinery_sunset),
    ;

    companion object {
        fun resolve(
            scene: TunnelWallpaperScene,
            whitelistMode: Boolean,
            darkTheme: Boolean,
        ): TunnelWallpaper {
            val time = when {
                whitelistMode -> TunnelWallpaperTime.Sunset
                darkTheme -> TunnelWallpaperTime.Night
                else -> TunnelWallpaperTime.Day
            }
            return entries.first { it.scene == scene && it.time == time }
        }

        @DrawableRes
        fun drawableRes(scene: TunnelWallpaperScene, time: TunnelWallpaperTime): Int =
            entries.first { it.scene == scene && it.time == time }.drawableRes
    }
}

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

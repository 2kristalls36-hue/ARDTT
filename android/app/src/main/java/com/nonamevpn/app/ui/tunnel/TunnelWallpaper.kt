package com.nonamevpn.app.ui.tunnel

import androidx.annotation.DrawableRes
import com.nonamevpn.app.R

enum class TunnelWallpaperScene {
    Field,
    City,
    Refinery,
    ;

    companion object {
        fun random(): TunnelWallpaperScene = entries.random()
    }
}

private enum class TunnelWallpaperTime {
    Day,
    Night,
    Sunset,
}

/** Illustrated tunnel tab backgrounds: field, cyberpunk city, refinery × day/night/sunset. */
enum class TunnelWallpaper(
    val scene: TunnelWallpaperScene,
    private val time: TunnelWallpaperTime,
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
    }
}

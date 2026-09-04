package com.ardtt.app.ui.tunnel

import androidx.annotation.DrawableRes
import com.ardtt.app.R

@DrawableRes
fun TunnelWallpaperScene.dayRes(): Int = when (this) {
    TunnelWallpaperScene.Field -> R.drawable.bg_tunnel_field_day
    TunnelWallpaperScene.City -> R.drawable.bg_tunnel_city_day
    TunnelWallpaperScene.Refinery -> R.drawable.bg_tunnel_refinery_day
}

@DrawableRes
fun TunnelWallpaperScene.nightRes(): Int = when (this) {
    TunnelWallpaperScene.Field -> R.drawable.bg_tunnel_field_night
    TunnelWallpaperScene.City -> R.drawable.bg_tunnel_city_night
    TunnelWallpaperScene.Refinery -> R.drawable.bg_tunnel_refinery_night
}

@DrawableRes
fun TunnelWallpaperScene.eveningRes(): Int = when (this) {
    TunnelWallpaperScene.Field -> R.drawable.bg_tunnel_field_sunset
    TunnelWallpaperScene.City -> R.drawable.bg_tunnel_city_sunset
    TunnelWallpaperScene.Refinery -> R.drawable.bg_tunnel_refinery_sunset
}

@DrawableRes
fun TunnelWallpaperId.drawableRes(): Int = when (time) {
    TunnelWallpaperTime.Day -> scene.dayRes()
    TunnelWallpaperTime.Night -> scene.nightRes()
    TunnelWallpaperTime.Evening -> scene.eveningRes()
}

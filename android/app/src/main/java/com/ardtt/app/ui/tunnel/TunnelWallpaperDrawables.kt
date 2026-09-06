package com.ardtt.app.ui.tunnel

import androidx.annotation.DrawableRes
import com.ardtt.app.R

@DrawableRes
fun TunnelWallpaperScene.dayRes(): Int = when (this) {
    TunnelWallpaperScene.Field -> R.drawable.bg_tunnel_field_day
    TunnelWallpaperScene.City -> R.drawable.bg_tunnel_city_day
    TunnelWallpaperScene.Refinery -> R.drawable.bg_tunnel_refinery_day
    TunnelWallpaperScene.Port -> R.drawable.bg_tunnel_port_day
    TunnelWallpaperScene.River -> R.drawable.bg_tunnel_river_day
    TunnelWallpaperScene.Lake -> R.drawable.bg_tunnel_lake_day
    TunnelWallpaperScene.Coast -> R.drawable.bg_tunnel_coast_day
    TunnelWallpaperScene.Dam -> R.drawable.bg_tunnel_dam_day
}

@DrawableRes
fun TunnelWallpaperScene.nightRes(): Int = when (this) {
    TunnelWallpaperScene.Field -> R.drawable.bg_tunnel_field_night
    TunnelWallpaperScene.City -> R.drawable.bg_tunnel_city_night
    TunnelWallpaperScene.Refinery -> R.drawable.bg_tunnel_refinery_night
    TunnelWallpaperScene.Port -> R.drawable.bg_tunnel_port_night
    TunnelWallpaperScene.River -> R.drawable.bg_tunnel_river_night
    TunnelWallpaperScene.Lake -> R.drawable.bg_tunnel_lake_night
    TunnelWallpaperScene.Coast -> R.drawable.bg_tunnel_coast_night
    TunnelWallpaperScene.Dam -> R.drawable.bg_tunnel_dam_night
}

@DrawableRes
fun TunnelWallpaperScene.eveningRes(): Int = when (this) {
    TunnelWallpaperScene.Field -> R.drawable.bg_tunnel_field_sunset
    TunnelWallpaperScene.City -> R.drawable.bg_tunnel_city_sunset
    TunnelWallpaperScene.Refinery -> R.drawable.bg_tunnel_refinery_sunset
    TunnelWallpaperScene.Port -> R.drawable.bg_tunnel_port_sunset
    TunnelWallpaperScene.River -> R.drawable.bg_tunnel_river_sunset
    TunnelWallpaperScene.Lake -> R.drawable.bg_tunnel_lake_sunset
    TunnelWallpaperScene.Coast -> R.drawable.bg_tunnel_coast_sunset
    TunnelWallpaperScene.Dam -> R.drawable.bg_tunnel_dam_sunset
}

@DrawableRes
fun TunnelWallpaperId.drawableRes(): Int = when (time) {
    TunnelWallpaperTime.Day -> scene.dayRes()
    TunnelWallpaperTime.Night -> scene.nightRes()
    TunnelWallpaperTime.Evening -> scene.eveningRes()
}

package com.nonamevpn.app.ui.tunnel

import androidx.annotation.DrawableRes
import com.nonamevpn.app.R

/** Illustrated tunnel tab backgrounds: field, cyberpunk city, refinery × day/night/sunset. */
enum class TunnelWallpaper(@DrawableRes val drawableRes: Int) {
    FieldDay(R.drawable.bg_tunnel_field_day),
    FieldNight(R.drawable.bg_tunnel_field_night),
    FieldSunset(R.drawable.bg_tunnel_field_sunset),
    CityDay(R.drawable.bg_tunnel_city_day),
    CityNight(R.drawable.bg_tunnel_city_night),
    CitySunset(R.drawable.bg_tunnel_city_sunset),
    RefineryDay(R.drawable.bg_tunnel_refinery_day),
    RefineryNight(R.drawable.bg_tunnel_refinery_night),
    RefinerySunset(R.drawable.bg_tunnel_refinery_sunset),
    ;

    companion object {
        fun random(): TunnelWallpaper = entries.random()
    }
}

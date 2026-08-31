package com.nonamevpn.app.ui.tunnel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nonamevpn.app.core.AppLog

/**
 * Decodes the process-chosen scene once. Tab switches must not call
 * [androidx.compose.ui.res.painterResource] — that re-decodes and briefly
 * paints Field (enum ordinal 0) before the real scene appears.
 */
object TunnelWallpaperCache {
    private val lock = Any()
    private var loadedScene: TunnelWallpaperScene? = null
    private val byTime = HashMap<TunnelWallpaperTime, Bitmap>(3)

    fun preload(context: Context, scene: TunnelWallpaperScene) {
        synchronized(lock) {
            if (loadedScene == scene && byTime.size == TIMES.size) return
            byTime.clear()
            val resources = context.resources
            for (time in TIMES) {
                val id = TunnelWallpaperId(scene, time).drawableRes()
                val bitmap = BitmapFactory.decodeResource(resources, id)
                if (bitmap == null) {
                    AppLog.e("TunnelWallpaper", "decode failed scene=$scene time=$time")
                    continue
                }
                byTime[time] = bitmap
            }
            loadedScene = scene
            AppLog.i(
                "TunnelWallpaper",
                "cached scene=$scene variants=${byTime.size}",
            )
        }
    }

    fun bitmap(scene: TunnelWallpaperScene, time: TunnelWallpaperTime): Bitmap? {
        synchronized(lock) {
            if (loadedScene != scene) return null
            return byTime[time]
        }
    }

    private val TIMES = listOf(
        TunnelWallpaperTime.Day,
        TunnelWallpaperTime.Night,
        TunnelWallpaperTime.Sunset,
    )
}

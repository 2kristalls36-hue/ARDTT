package com.nonamevpn.app.ui.tunnel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nonamevpn.app.core.AppLog

/**
 * Decodes the process-chosen scene once (day, night, evening).
 * Tab switches must not call [androidx.compose.ui.res.painterResource] —
 * that re-decodes and can flash Field (enum ordinal 0).
 */
object TunnelWallpaperCache {
    private val lock = Any()
    private var loadedScene: TunnelWallpaperScene? = null
    private val byTime = HashMap<TunnelWallpaperTime, Bitmap>(3)
    private val averageColors = HashMap<TunnelWallpaperTime, androidx.compose.ui.graphics.Color>(3)

    fun preload(context: Context, scene: TunnelWallpaperScene) {
        synchronized(lock) {
            if (loadedScene == scene && byTime.size == TIMES.size) return
            byTime.clear()
            averageColors.clear()
            val resources = context.resources
            for (time in TIMES) {
                val id = TunnelWallpaperId(scene, time).drawableRes()
                val bitmap = BitmapFactory.decodeResource(resources, id)
                if (bitmap == null) {
                    AppLog.e("TunnelWallpaper", "decode failed scene=$scene time=$time")
                    continue
                }
                byTime[time] = bitmap
                averageColors[time] = calculateAverageColor(bitmap)
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

    fun averageColor(scene: TunnelWallpaperScene, time: TunnelWallpaperTime): androidx.compose.ui.graphics.Color? {
        synchronized(lock) {
            if (loadedScene != scene) return null
            return averageColors[time]
        }
    }

    private fun calculateAverageColor(bitmap: Bitmap): androidx.compose.ui.graphics.Color {
        // Sample pixels to calculate average color
        val stepX = (bitmap.width / 20).coerceAtLeast(1)
        val stepY = (bitmap.height / 20).coerceAtLeast(1)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                count++
            }
        }
        if (count == 0) return androidx.compose.ui.graphics.Color.Transparent
        return androidx.compose.ui.graphics.Color(
            red = (r / count).toInt(),
            green = (g / count).toInt(),
            blue = (b / count).toInt(),
            alpha = 255
        )
    }

    private val TIMES = listOf(
        TunnelWallpaperTime.Day,
        TunnelWallpaperTime.Night,
        TunnelWallpaperTime.Evening,
    )
}

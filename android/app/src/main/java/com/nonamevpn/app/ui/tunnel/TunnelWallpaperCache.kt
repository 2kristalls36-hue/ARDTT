package com.nonamevpn.app.ui.tunnel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import com.nonamevpn.app.core.AppLog
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes the process-chosen scene once. Tab switches must not call
 * [androidx.compose.ui.res.painterResource] — that re-decodes and briefly
 * paints Field (enum ordinal 0) before the real scene appears.
 */
object TunnelWallpaperCache {
    private val lock = Any()
    private var loadedScene: TunnelWallpaperScene? = null
    private val byTime = HashMap<TunnelWallpaperTime, Bitmap>(3)
    private val accentByTime = HashMap<TunnelWallpaperTime, Int>(3)

    fun preload(context: Context, scene: TunnelWallpaperScene) {
        synchronized(lock) {
            if (loadedScene == scene && byTime.size == TIMES.size) return
            byTime.clear()
            accentByTime.clear()
            val resources = context.resources
            for (time in TIMES) {
                val id = TunnelWallpaperId(scene, time).drawableRes()
                val bitmap = BitmapFactory.decodeResource(resources, id)
                if (bitmap == null) {
                    AppLog.e("TunnelWallpaper", "decode failed scene=$scene time=$time")
                    continue
                }
                byTime[time] = bitmap
                accentByTime[time] = extractAccentArgb(bitmap)
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

    fun accentArgb(scene: TunnelWallpaperScene, time: TunnelWallpaperTime): Int? {
        synchronized(lock) {
            if (loadedScene != scene) return null
            return accentByTime[time]
        }
    }

    private fun extractAccentArgb(bitmap: Bitmap): Int {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val xStep = max(1, width / 48)
        val yStep = max(1, height / 48)

        var totalWeight = 0f
        var accR = 0f
        var accG = 0f
        var accB = 0f
        for (y in 0 until height step yStep) {
            for (x in 0 until width step xStep) {
                val c = bitmap.getPixel(x, y)
                if (AndroidColor.alpha(c) < 180) continue
                val r = AndroidColor.red(c) / 255f
                val g = AndroidColor.green(c) / 255f
                val b = AndroidColor.blue(c) / 255f
                val maxC = max(r, max(g, b))
                val minC = minOf(r, g, b)
                val sat = if (maxC <= 0f) 0f else (maxC - minC) / maxC
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val midLumWeight = 1f - abs(lum - 0.55f)
                val weight = 0.20f + sat * 0.65f + midLumWeight * 0.35f
                accR += r * weight
                accG += g * weight
                accB += b * weight
                totalWeight += weight
            }
        }
        if (totalWeight <= 0f) return AndroidColor.rgb(109, 76, 65) // theme fallback

        var r = (accR / totalWeight).coerceIn(0f, 1f)
        var g = (accG / totalWeight).coerceIn(0f, 1f)
        var b = (accB / totalWeight).coerceIn(0f, 1f)
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (lum < 0.28f) {
            val t = ((0.28f - lum) * 1.6f).coerceIn(0f, 0.55f)
            r = r + (1f - r) * t
            g = g + (1f - g) * t
            b = b + (1f - b) * t
        } else if (lum > 0.78f) {
            val t = ((lum - 0.78f) * 1.8f).coerceIn(0f, 0.55f)
            r *= (1f - t)
            g *= (1f - t)
            b *= (1f - t)
        }
        return AndroidColor.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    private val TIMES = listOf(
        TunnelWallpaperTime.Day,
        TunnelWallpaperTime.Night,
        TunnelWallpaperTime.Sunset,
    )
}

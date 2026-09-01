package com.nonamevpn.app.ui.tunnel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale

/**
 * Full-screen illustrated wallpaper for user-mode tabs.
 *
 * Draws process-cached bitmaps for the chosen scene only. Field is enum
 * ordinal 0 — never load it as a placeholder while another scene decodes.
 */
@Composable
fun TunnelWallpaperBackdrop(
    wallpaper: TunnelWallpaperId,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val overlayColor = if (isDark) {
        Color(0xFF0F0E13).copy(alpha = 0.42f)
    } else {
        Color(0xFFF7F5F0).copy(alpha = 0.30f)
    }
    val scene = wallpaper.scene
    val dayBmp = TunnelWallpaperCache.bitmap(scene, TunnelWallpaperTime.Day)
    val nightBmp = TunnelWallpaperCache.bitmap(scene, TunnelWallpaperTime.Night)
    val sunsetBmp = TunnelWallpaperCache.bitmap(scene, TunnelWallpaperTime.Sunset)
    val day = remember(dayBmp) { dayBmp?.asImageBitmap() }
    val night = remember(nightBmp) { nightBmp?.asImageBitmap() }
    val sunset = remember(sunsetBmp) { sunsetBmp?.asImageBitmap() }
    val imageBitmap = when (wallpaper.time) {
        TunnelWallpaperTime.Day -> day
        TunnelWallpaperTime.Night -> night
        TunnelWallpaperTime.Sunset -> sunset
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayColor),
        )
    }
}

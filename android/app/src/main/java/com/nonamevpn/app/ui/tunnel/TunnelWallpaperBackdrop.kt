package com.nonamevpn.app.ui.tunnel

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.layout.ContentScale

/**
 * Full-screen illustrated wallpaper for every user-mode tab, including Tunnel.
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
    val overlayColor = when (wallpaper.time) {
        TunnelWallpaperTime.Night -> Color(0xFF0F0E13).copy(alpha = 0.20f)
        TunnelWallpaperTime.Evening -> Color(0xFF1A120C).copy(alpha = 0.15f)
        TunnelWallpaperTime.Day -> Color(0xFFF7F5F0).copy(alpha = 0.15f)
    }
    val scene = wallpaper.scene
    val dayBmp = TunnelWallpaperCache.bitmap(scene, TunnelWallpaperTime.Day)
    val nightBmp = TunnelWallpaperCache.bitmap(scene, TunnelWallpaperTime.Night)
    val eveningBmp = TunnelWallpaperCache.bitmap(scene, TunnelWallpaperTime.Evening)
    val day = remember(dayBmp) { dayBmp?.asImageBitmap() }
    val night = remember(nightBmp) { nightBmp?.asImageBitmap() }
    val evening = remember(eveningBmp) { eveningBmp?.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Crossfade(
            targetState = wallpaper.time,
            animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing),
            label = "tunnel_wallpaper_time",
        ) { time ->
            val imageBitmap = when (time) {
                TunnelWallpaperTime.Day -> day
                TunnelWallpaperTime.Night -> night
                TunnelWallpaperTime.Evening -> evening
            }
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayColor),
        )
    }
}

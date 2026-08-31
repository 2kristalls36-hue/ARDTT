package com.nonamevpn.app.ui.tunnel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * Full-screen illustrated wallpaper for the Tunnel tab.
 *
 * All three time-of-day painters for the chosen scene stay composed so tab
 * switches and БС/theme flips do not re-decode and flash another scene.
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
    val day = painterResource(id = wallpaper.scene.dayRes())
    val night = painterResource(id = wallpaper.scene.nightRes())
    val sunset = painterResource(id = wallpaper.scene.sunsetRes())
    val painter = when (wallpaper.time) {
        TunnelWallpaperTime.Day -> day
        TunnelWallpaperTime.Night -> night
        TunnelWallpaperTime.Sunset -> sunset
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayColor),
        )
    }
}

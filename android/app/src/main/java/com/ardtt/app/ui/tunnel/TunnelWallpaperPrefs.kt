package com.ardtt.app.ui.tunnel

import android.content.Context

private const val PREFS = "tunnel_wallpaper"
private const val KEY_LAST = "last_scene"
private const val KEY_SEEN = "seen_scenes"

/** Persist rotation so НПЗ is not skipped across cold starts. */
fun loadNextTunnelWallpaperScene(context: Context): TunnelWallpaperScene {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val previous = prefs.getString(KEY_LAST, null)
    val seen = prefs.getString(KEY_SEEN, "").orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    val (picked, nextSeen) = nextTunnelWallpaperScene(previous, seen)
    prefs.edit()
        .putString(KEY_LAST, picked.name)
        .putString(KEY_SEEN, nextSeen.joinToString(","))
        .commit()
    return picked
}

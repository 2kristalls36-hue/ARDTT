package com.ardtt.app.telemetry.collectors

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject

object DeviceInfoCollector {
    fun snapshot(context: Context): JSONObject {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)

        val stat = runCatching {
            StatFs(Environment.getDataDirectory().absolutePath)
        }.getOrNull()

        val battery = batterySnapshot(context)

        return JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("os_version", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("screen", JSONObject()
                .put("width_px", metrics.widthPixels)
                .put("height_px", metrics.heightPixels)
                .put("density", metrics.density))
            .put("ram", JSONObject()
                .put("total_bytes", mem.totalMem)
                .put("avail_bytes", mem.availMem)
                .put("low_memory", mem.lowMemory))
            .put("storage", JSONObject().apply {
                if (stat != null) {
                    val block = stat.blockSizeLong
                    put("total_bytes", stat.blockCountLong * block)
                    put("avail_bytes", stat.availableBlocksLong * block)
                }
            })
            .put("battery", battery)
    }

    private fun batterySnapshot(context: Context): JSONObject {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return JSONObject()
            .put("percent", pct)
            .put("charging", charging)
            .put("status", status)
    }
}

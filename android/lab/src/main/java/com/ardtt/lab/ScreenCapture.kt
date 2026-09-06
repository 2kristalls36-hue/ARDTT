package com.ardtt.lab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScreenCaptureStore {
    @Volatile
    private var projection: MediaProjection? = null

    fun granted(): Boolean = projection != null

    fun accept(context: Context, resultCode: Int, data: Intent?) {
        clear()
        if (resultCode != Activity.RESULT_OK || data == null) return
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val created = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull() ?: return
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === created) projection = null
            }
        }, null)
        projection = created
    }

    fun current(): MediaProjection? = projection

    fun clear() {
        val old = projection
        projection = null
        runCatching { old?.stop() }
    }
}

class ScreenCapture(private val context: Context) {
    fun capturePng(timeoutMs: Long = 4_000): ByteArray? {
        val projection = ScreenCaptureStore.current() ?: return null
        val thread = HandlerThread("ardtt-lab-cap").apply { start() }
        val handler = Handler(thread.looper)
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        return try {
            val metrics = DisplayMetrics()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val dpi = metrics.densityDpi.coerceAtLeast(160)
            val latch = CountDownLatch(1)
            val out = arrayOfNulls<ByteArray>(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ src ->
                val image = src.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (out[0] == null) {
                        out[0] = imageToPng(image, width, height)
                        latch.countDown()
                    }
                } finally {
                    image.close()
                }
            }, handler)
            display = projection.createVirtualDisplay(
                "ardtt-lab",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            out[0]
        } catch (_: Exception) {
            null
        } finally {
            runCatching { display?.release() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
    }

    private fun imageToPng(image: android.media.Image, width: Int, height: Int): ByteArray {
        val plane = image.planes[0]
        val buf = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buf)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        val bytes = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        if (cropped !== bitmap) bitmap.recycle()
        cropped.recycle()
        return bytes.toByteArray()
    }
}

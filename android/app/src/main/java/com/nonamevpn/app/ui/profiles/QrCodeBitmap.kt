package com.nonamevpn.app.ui.profiles

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class QrBitmapState(
    val loading: Boolean = false,
    val bitmap: ImageBitmap? = null,
)

@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 512): QrBitmapState {
    val state by produceState(initialValue = QrBitmapState(loading = content.isNotBlank()), content, sizePx) {
        if (content.isBlank()) {
            value = QrBitmapState()
            return@produceState
        }
        value = QrBitmapState(loading = true)
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { encodeQr(content, sizePx) }.getOrNull()
        }
        value = QrBitmapState(loading = false, bitmap = bitmap)
    }
    return state
}

private fun encodeQr(content: String, sizePx: Int): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = matrix.width
    val height = matrix.height
    require(width > 0 && height > 0) { "QR matrix is empty" }
    val pixels = IntArray(width * height)
    var index = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[index++] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
    return bmp.asImageBitmap()
}

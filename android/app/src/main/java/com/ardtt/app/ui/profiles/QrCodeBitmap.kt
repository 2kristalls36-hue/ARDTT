package com.ardtt.app.ui.profiles

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class QrBitmapState(
    val loading: Boolean = false,
    val bitmap: ImageBitmap? = null,
)

internal const val QR_QUIET_MODULES = 4
internal const val QR_MODULE_PX = 12

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

/** Full-width black-on-white QR. No rounded clip on the modules — cameras need intact finders. */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "QR-код профиля",
) {
    val qr = rememberQrBitmap(content, 1024)
    val bitmap = qr.bitmap ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(16.dp),
        )
    }
}

internal fun encodeQrMatrix(content: String): BitMatrix {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to QR_QUIET_MODULES,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
    )
    return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
}

internal fun decodeQrFromMatrix(matrix: BitMatrix, scale: Int = QR_MODULE_PX): String {
    val px = scale.coerceAtLeast(4)
    val width = matrix.width * px
    val height = matrix.height * px
    val pixels = IntArray(width * height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            val color = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            val destX = x * px
            val destY = y * px
            var dy = 0
            while (dy < px) {
                val row = (destY + dy) * width + destX
                var dx = 0
                while (dx < px) {
                    pixels[row + dx] = color
                    dx++
                }
                dy++
            }
        }
    }
    val source = RGBLuminanceSource(width, height, pixels)
    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )
    return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
}

internal fun encodeQrBitmap(content: String, modulePx: Int = QR_MODULE_PX): Bitmap {
    val matrix = encodeQrMatrix(content)
    val width = matrix.width
    val height = matrix.height
    val px = modulePx.coerceAtLeast(1)
    val outW = width * px
    val outH = height * px
    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val row = IntArray(outW)
    for (y in 0 until height) {
        var x = 0
        while (x < width) {
            val color = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
            val dest = x * px
            var dx = 0
            while (dx < px) {
                row[dest + dx] = color
                dx++
            }
            x++
        }
        var dy = 0
        while (dy < px) {
            bmp.setPixels(row, 0, outW, 0, y * px + dy, outW, 1)
            dy++
        }
    }
    return bmp
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
            pixels[index++] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
    return bmp.asImageBitmap()
}

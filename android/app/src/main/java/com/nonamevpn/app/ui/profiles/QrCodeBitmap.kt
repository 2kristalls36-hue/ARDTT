package com.nonamevpn.app.ui.profiles

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal const val QR_QUIET_MODULES = 4
internal const val QR_MODULE_PX = 12

@Composable
fun rememberQrBitmap(content: String): ImageBitmap? =
    remember(content) {
        if (content.isBlank()) {
            null
        } else {
            runCatching { encodeQrBitmap(content).asImageBitmap() }.getOrNull()
        }
    }

/** Full-width black-on-white QR. No rounded clip on the modules — cameras need intact finders. */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "QR-код профиля",
) {
    val qr = rememberQrBitmap(content) ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Image(
            bitmap = qr,
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

internal fun bitMatrixToRgb(matrix: BitMatrix): IntArray {
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    var i = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[i++] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return pixels
}

internal fun decodeQrFromMatrix(matrix: BitMatrix): String {
    val source = RGBLuminanceSource(matrix.width, matrix.height, bitMatrixToRgb(matrix))
    return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
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

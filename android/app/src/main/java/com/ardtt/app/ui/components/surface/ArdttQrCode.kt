package com.ardtt.app.ui.components.surface

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSpacing
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

/** Quiet zone required by the QR spec, in modules. */
internal const val QR_QUIET_MODULES = 4

/** Module size used when rasterising a matrix for decoding. */
internal const val QR_MODULE_PX = 12

/** Target edge length of a rendered QR, in pixels. */
private const val QR_RENDER_PX = 1024

data class ArdttQrCodeState(
    val loading: Boolean = false,
    val bitmap: ImageBitmap? = null,
)

/**
 * Encodes [content] off the main thread.
 *
 * There used to be three encoders here — one for display with a one-module
 * margin, one for tests with the spec quiet zone, and one unused bitmap
 * variant. All rendering now goes through [encodeQrMatrix], so what a camera
 * sees is exactly what the round-trip test decodes.
 */
@Composable
fun rememberQrCode(content: String, sizePx: Int = QR_RENDER_PX): ArdttQrCodeState {
    val state by produceState(
        initialValue = ArdttQrCodeState(loading = content.isNotBlank()),
        content,
        sizePx,
    ) {
        if (content.isBlank()) {
            value = ArdttQrCodeState()
            return@produceState
        }
        value = ArdttQrCodeState(loading = true)
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { encodeQrImage(content, sizePx) }.getOrNull()
        }
        value = ArdttQrCodeState(loading = false, bitmap = bitmap)
    }
    return state
}

/**
 * Full-width black-on-white QR. The modules are never clipped or filtered —
 * cameras need intact finder patterns.
 */
@Composable
fun ArdttQrCode(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    contentDescription: String = "QR-код",
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ArdttShapes.Chip,
        color = Color.White,
        shadowElevation = ArdttElevation.None,
        tonalElevation = ArdttElevation.None,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(ArdttSpacing.Large),
        )
    }
}

/** Encodes and renders in one step for callers that do not need the state. */
@Composable
fun ArdttQrCode(
    content: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "QR-код",
) {
    val bitmap = rememberQrCode(content).bitmap ?: return
    ArdttQrCode(bitmap = bitmap, modifier = modifier, contentDescription = contentDescription)
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
    val pixels = rasterize(matrix, scale.coerceAtLeast(4))
    val width = matrix.width * scale.coerceAtLeast(4)
    val height = matrix.height * scale.coerceAtLeast(4)
    val source = RGBLuminanceSource(width, height, pixels)
    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )
    return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
}

private fun encodeQrImage(content: String, sizePx: Int): ImageBitmap {
    val matrix = encodeQrMatrix(content)
    require(matrix.width > 0 && matrix.height > 0) { "QR matrix is empty" }
    val scale = (sizePx / matrix.width).coerceAtLeast(1)
    val width = matrix.width * scale
    val height = matrix.height * scale
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(rasterize(matrix, scale), 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}

/** Expands each matrix module into a [scale]×[scale] block of ARGB pixels. */
private fun rasterize(matrix: BitMatrix, scale: Int): IntArray {
    val width = matrix.width * scale
    val pixels = IntArray(width * matrix.height * scale)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            val color = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
            for (dy in 0 until scale) {
                val row = (y * scale + dy) * width + x * scale
                for (dx in 0 until scale) {
                    pixels[row + dx] = color
                }
            }
        }
    }
    return pixels
}

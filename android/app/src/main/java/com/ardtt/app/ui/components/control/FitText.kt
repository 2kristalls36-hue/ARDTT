package com.ardtt.app.ui.components.control

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * Text for controls of fixed width: with a large system font a label that does not fit
 * is scaled down a little (to [minScale] at most) instead of being cut or broken mid-word.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = 1,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Center,
    minScale: Float = 0.62f,
    /** Upper bound on the scale, so a row of labels can share the smallest one. */
    scaleCap: Float = 1f,
    /** The scale this text needs to fit on its own. */
    onScale: ((Float) -> Unit)? = null,
) {
    val base: TextUnit = style.fontSize.takeIf { it.isSpecified } ?: LocalTextStyle.current.fontSize
    var scale by remember(text, base) { mutableFloatStateOf(1f) }
    var ready by remember(text, base) { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        style = style.copy(fontSize = base * minOf(scale, scaleCap)),
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        onTextLayout = { layout ->
            if (doesNotFit(layout, text) && scale > minScale && scale <= scaleCap) {
                scale -= 0.05f
            } else {
                ready = true
                onScale?.invoke(scale)
            }
        },
    )
}

private fun doesNotFit(layout: TextLayoutResult, text: String): Boolean {
    if (layout.hasVisualOverflow) return true
    // A word broken across lines also counts as not fitting.
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        if (end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace() && text[end - 1] != '-') return true
    }
    return false
}

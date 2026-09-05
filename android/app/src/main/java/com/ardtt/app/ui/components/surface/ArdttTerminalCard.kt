package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.isDarkSurface

/** Default height cap of an inline log view. */
private val TerminalMaxHeight = 240.dp

/** Same terminal fill as the Logs tab card. */
@Composable
fun terminalCardColor(): Color {
    val dark = isDarkSurface()
    val base = if (dark) {
        ArdttColors.TerminalBgDark
    } else {
        lerp(ArdttColors.TerminalBg, MaterialTheme.colorScheme.surface, 0.35f)
    }
    return base.copy(alpha = if (dark) 0.90f else 1.00f)
}

@Composable
fun terminalCardElevation(): Dp =
    if (isDarkSurface()) ArdttElevation.Card else ArdttElevation.Low

/** Scrollable monospace log on the Logs-tab terminal chrome. */
@Composable
fun ArdttTerminalCard(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = TerminalMaxHeight,
    emptyText: String = "—",
) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) {
        scroll.scrollTo(scroll.maxValue)
    }
    ArdttSectionCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = ArdttLayout.CompactCardPadding,
        verticalArrangement = Arrangement.Top,
        shape = ArdttShapes.Panel,
        color = terminalCardColor(),
        shadowElevation = terminalCardElevation(),
    ) {
        Text(
            text = text.ifBlank { emptyText },
            color = ArdttColors.TerminalText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(scroll),
        )
    }
}

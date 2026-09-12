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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttTerminalTextStyle
import com.ardtt.app.ui.theme.cardContainerColor
import com.ardtt.app.ui.theme.isDarkSurface

/** Default height cap of an inline log view. */
private val TerminalMaxHeight = 240.dp

/** Opaque theme surface — not a translucent green terminal on wallpaper. */
@Composable
fun terminalCardColor(): Color = cardContainerColor()

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
            color = MaterialTheme.colorScheme.onSurface,
            style = ArdttTerminalTextStyle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(scroll),
        )
    }
}

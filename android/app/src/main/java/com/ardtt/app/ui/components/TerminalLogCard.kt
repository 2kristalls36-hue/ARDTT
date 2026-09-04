package com.ardtt.app.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Same terminal fill as the Logs tab card. */
@Composable
fun terminalLogCardColor(): Color {
    val isDark = isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.surface
    val base = if (isDark) {
        ArdttColors.terminalBgDark
    } else {
        lerp(ArdttColors.terminalBg, surface, 0.35f)
    }
    return base.copy(alpha = if (isDark) 0.90f else 1.00f)
}

@Composable
fun terminalLogCardShadow(): Dp = if (isSystemInDarkTheme()) 4.dp else 2.dp

/** Scrollable monospace log on the Logs-tab terminal chrome. */
@Composable
fun TerminalLogCard(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 240.dp,
    emptyText: String = "—",
) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) {
        scroll.scrollTo(scroll.maxValue)
    }
    AppSectionCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Top,
        shape = RoundedCornerShape(24.dp),
        color = terminalLogCardColor(),
        shadowElevation = terminalLogCardShadow(),
        showBorder = false,
    ) {
        Text(
            text = text.ifBlank { emptyText },
            color = ArdttColors.terminalText,
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

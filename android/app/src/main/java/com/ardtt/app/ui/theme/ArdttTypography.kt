package com.ardtt.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ardtt.app.R

/** Inter (SIL OFL). */
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun inter(
    weight: FontWeight,
    size: Int,
    lineHeight: Int,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing,
)

val ArdttTypography = Typography(
    displayLarge = inter(FontWeight.Bold, 57, 64),
    displayMedium = inter(FontWeight.Bold, 45, 52),
    displaySmall = inter(FontWeight.Bold, 36, 44),
    headlineLarge = inter(FontWeight.SemiBold, 32, 40),
    headlineMedium = inter(FontWeight.SemiBold, 28, 36),
    headlineSmall = inter(FontWeight.SemiBold, 24, 32),
    titleLarge = inter(FontWeight.SemiBold, 22, 28),
    titleMedium = inter(FontWeight.SemiBold, 16, 24, 0.15.sp),
    titleSmall = inter(FontWeight.Medium, 14, 20, 0.1.sp),
    bodyLarge = inter(FontWeight.Normal, 16, 24, 0.5.sp),
    bodyMedium = inter(FontWeight.Normal, 14, 20, 0.25.sp),
    bodySmall = inter(FontWeight.Normal, 12, 16, 0.4.sp),
    labelLarge = inter(FontWeight.Medium, 14, 20, 0.1.sp),
    labelMedium = inter(FontWeight.Medium, 12, 16, 0.5.sp),
    labelSmall = inter(FontWeight.Medium, 11, 16, 0.5.sp),
)

package com.ara.timer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AraColors {
    val Background = Color(0xFF07090D)
    val Surface = Color(0xFF0F141B)
    val SurfaceHigh = Color(0xFF161D27)
    val Outline = Color(0xFF212A36)
    val Track = Color(0xFF18202B)
    val TextPrimary = Color(0xFFF2F5F9)
    val TextSecondary = Color(0xFF8C96A6)

    val Green = Color(0xFF22C55E)
    val GreenLight = Color(0xFF4ADE80)
    val OnGreen = Color(0xFF03200E)

    val Red = Color(0xFFDC2626)
    val RedLight = Color(0xFFEF4444)
    val OnRed = Color(0xFFFFFFFF)

    val Amber = Color(0xFFFBBF24)
}

@Composable
fun AraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AraColors.Green,
            onPrimary = AraColors.OnGreen,
            secondary = AraColors.Amber,
            error = AraColors.RedLight,
            background = AraColors.Background,
            onBackground = AraColors.TextPrimary,
            surface = AraColors.Surface,
            onSurface = AraColors.TextPrimary,
            surfaceVariant = AraColors.SurfaceHigh,
            onSurfaceVariant = AraColors.TextSecondary,
            outline = AraColors.Outline,
            outlineVariant = AraColors.Outline,
        ),
        content = content,
    )
}

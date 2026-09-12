package com.samsung.screenrecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Samsung One UI 6 Iconic Blue and Accent Tones
val SamsungBlue = Color(0xFF0C66E4)
val SamsungDarkBlue = Color(0xFF0052CC)
val SamsungCardLight = Color(0xFFF4F5F7)
val SamsungCardDark = Color(0xFF1E1F22)

private val LightColorScheme = lightColorScheme(
    primary = SamsungBlue,
    secondary = Color(0xFF00875A),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = SamsungCardLight,
    onPrimary = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF579DFF),
    secondary = Color(0xFF57D9A3),
    background = Color(0xFF121316),
    surface = Color(0xFF16171B),
    surfaceVariant = SamsungCardDark,
    onPrimary = Color.Black
)

@Composable
fun SamsungScreenRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
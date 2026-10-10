package com.brostreamah.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Accent = Color(0xFFE53935)

@Composable
fun BroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.White,
            background = Color(0xFF0B0B0D), onBackground = Color(0xFFF2F2F2),
            surface = Color(0xFF1A1A1F), onSurface = Color(0xFFF2F2F2),
            surfaceVariant = Color(0xFF26262C), onSurfaceVariant = Color(0xFFBDBDC4),
        ),
        content = content,
    )
}

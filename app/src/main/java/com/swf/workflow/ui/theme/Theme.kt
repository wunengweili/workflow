package com.swf.workflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0F172A),
    onPrimary = Color.White,
    secondary = Color(0xFF1F2937),
    onSecondary = Color.White,
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE5E7EB),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF111827),
    background = Color(0xFF111827),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF1F2937),
    onSurface = Color(0xFFE5E7EB)
)

@Composable
fun WorkflowTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}

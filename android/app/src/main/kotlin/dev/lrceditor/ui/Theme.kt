package dev.lrceditor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Studio {
    val Bg = Color(0xFF12151C)
    val Panel = Color(0xFF181C25)
    val Panel2 = Color(0xFF1F2430)
    val Border = Color(0xFF2A303D)
    val Text = Color(0xFFE8EAF0)
    val Dim = Color(0xFF8B93A7)
    val Faint = Color(0xFF5B6478)
    val Amber = Color(0xFFE8A33D)   // timestamps, playhead, primary actions
    val Teal = Color(0xFF4FA8A0)    // selected line
    val Warn = Color(0xFFD97757)    // out of order
    val Danger = Color(0xFFD9534F)
}

private val scheme = darkColorScheme(
    primary = Studio.Amber, onPrimary = Color(0xFF1A1305),
    secondary = Studio.Teal, onSecondary = Color(0xFF04201D),
    background = Studio.Bg, onBackground = Studio.Text,
    surface = Studio.Panel, onSurface = Studio.Text,
    surfaceVariant = Studio.Panel2, onSurfaceVariant = Studio.Dim,
    surfaceContainer = Studio.Panel, surfaceContainerHigh = Studio.Panel2,
    outline = Studio.Border, error = Studio.Danger,
)

@Composable
fun LrcTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

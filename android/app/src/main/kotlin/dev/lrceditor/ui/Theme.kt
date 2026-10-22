package dev.lrceditor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

object Studio {
    /** Pure black backgrounds for OLED screens (Settings > Appearance). Set by [LrcTheme]; reading it makes the screen redraw when it changes. */
    var oled by mutableStateOf(false)

    val Bg get() = if (oled) Color.Black else Color(0xFF12151C)
    val Panel get() = if (oled) Color(0xFF080808) else Color(0xFF181C25)
    val Panel2 get() = if (oled) Color(0xFF141414) else Color(0xFF1F2430)
    val Border get() = if (oled) Color(0xFF262626) else Color(0xFF2A303D)
    val Text = Color(0xFFE8EAF0)
    val Dim = Color(0xFF8B93A7)
    val Faint = Color(0xFF5B6478)
    val Amber = Color(0xFFE8A33D)   // timestamps, playhead, primary actions
    val Teal = Color(0xFF4FA8A0)    // selected line
    val Warn = Color(0xFFD97757)    // out of order
    val Danger = Color(0xFFD9534F)
}

private fun scheme() = darkColorScheme(
    primary = Studio.Amber, onPrimary = Color(0xFF1A1305),
    secondary = Studio.Teal, onSecondary = Color(0xFF04201D),
    background = Studio.Bg, onBackground = Studio.Text,
    surface = Studio.Panel, onSurface = Studio.Text,
    surfaceVariant = Studio.Panel2, onSurfaceVariant = Studio.Dim,
    surfaceContainer = Studio.Panel, surfaceContainerHigh = Studio.Panel2,
    outline = Studio.Border, error = Studio.Danger,
)

@Composable
fun LrcTheme(oled: Boolean = false, content: @Composable () -> Unit) {
    if (Studio.oled != oled) Studio.oled = oled
    MaterialTheme(colorScheme = scheme(), content = content)
}

/** Short screens (e.g. the Unihertz Jelly Star) get tighter layouts. */
@Composable
fun isCompactScreen(): Boolean = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value } < 700

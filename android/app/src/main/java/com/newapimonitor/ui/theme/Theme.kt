package com.newapimonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.newapimonitor.data.store.Appearance
import androidx.compose.runtime.Composable

/**
 * 永遠深色（App 係深色玻璃設計，唔跟系統淺色）。
 */
private fun darkColors() = darkColorScheme(
    primary = Accent1,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = Accent2,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF06222A),
    tertiary = androidx.compose.ui.graphics.Color(0xFFF472B6),
    background = BgColor,
    onBackground = TxtColor,
    surface = Bg2Color,
    onSurface = TxtColor,
    surfaceVariant = Bg3Color,
    onSurfaceVariant = Txt2Color,
    outline = CardStrokeColor,
    outlineVariant = StrokeSoft,
    error = ErrRed,
    onError = androidx.compose.ui.graphics.Color(0xFF2A0A0A),
)

@Composable
fun NewApiMonitorTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    applyAppearance(appearance.mode, appearance.accent)
    MaterialTheme(
        colorScheme = if (appearance.mode == "light") lightColorScheme(
            primary = Accent1, onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = Accent2, background = BgColor, onBackground = TxtColor,
            surface = Bg2Color, onSurface = TxtColor, surfaceVariant = Bg3Color,
            onSurfaceVariant = Txt2Color, outline = CardStrokeColor,
        ) else darkColors(),
        content = content,
    )
}

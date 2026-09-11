package com.huankongyu.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = IslandBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = IslandGreen,
    background = IslandSurface,
    surface = androidx.compose.ui.graphics.Color.White,
    onBackground = IslandInk,
    onSurface = IslandInk,
    outline = IslandLine
)

private val DarkColors = darkColorScheme(
    primary = IslandBlueDark,
    secondary = IslandGreen,
    background = androidx.compose.ui.graphics.Color(0xFF0D1422),
    surface = androidx.compose.ui.graphics.Color(0xFF151F30),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE9EEFA),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE9EEFA),
    outline = androidx.compose.ui.graphics.Color(0xFF34405A)
)

@Composable
fun HuankongyuTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}

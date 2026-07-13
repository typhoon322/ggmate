package com.gagmate.app.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Mocha,
    onPrimary = Color.White,
    primaryContainer = Cappuccino,
    onPrimaryContainer = Espresso,
    secondary = Latte,
    onSecondary = Color.White,
    secondaryContainer = Cream,
    onSecondaryContainer = Espresso,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = Color.White,
    onSurface = OnSurfaceLight,
    surfaceVariant = Cream,
    onSurfaceVariant = OnSurfaceLight,
    outline = Outline,
    error = StatusError,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Espresso,
    primaryContainer = Espresso,
    onPrimaryContainer = Cream,
    secondary = DarkSecondary,
    onSecondary = Color.Black,
    secondaryContainer = Mocha,
    onSecondaryContainer = Cream,
    background = DarkBackground,
    onBackground = OnSurfaceDark,
    surface = DarkSurface,
    onSurface = OnSurfaceDark,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = OnSurfaceDark,
    outline = Outline,
    error = StatusError,
    onError = Color.White
)

@Composable
fun GagMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GagMateTypography,
        content = content
    )
}

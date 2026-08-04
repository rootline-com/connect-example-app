package com.rootline.connect.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ExampleColorScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    secondary = SurfaceGray,
    onSecondary = OnSurface,
    tertiary = CoralDark,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = OnSurface,
    surface = SurfaceGray,
    onSurface = OnSurface,
    surfaceVariant = SurfaceGray,
    onSurfaceVariant = Muted,
    error = ErrorRed,
    onError = Color.White,
)

/** Fixed Rootline look — the example app has no synced branding, so the scheme is static. */
@Composable
fun ExampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ExampleColorScheme,
        typography = Typography,
        content = content,
    )
}

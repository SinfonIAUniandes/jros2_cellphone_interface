package com.jros2.cellphone_interface.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SensorDarkScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color.Black,
    secondary = PurpleAccent,
    onSecondary = Color.Black,
    tertiary = GreenActive,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = DarkCardBorder,
    error = RedInactive
)

private val SensorLightScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun Jros2_cellphone_interfaceTheme(
    darkTheme: Boolean = true, // Force dark by default for sensor dashboard
    content: @Composable () -> Unit
) {
    // Always use our custom dark scheme for the sensor dashboard
    val colorScheme = if (darkTheme) SensorDarkScheme else SensorLightScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
package com.cusapps.onliecodes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    secondary = SecondaryPink,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = TextWhite,
    onSurface = TextWhite
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryDarkPurple,
    secondary = SecondaryDarkPink,
    background = BackgroundLight,
    surface = SurfaceLight,
    onBackground = TextBlack,
    onSurface = TextBlack
)

@Composable
fun OnlieCodesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val editorPalette = if (darkTheme) DarkEditorPalette else LightEditorPalette
    CompositionLocalProvider(LocalEditorPalette provides editorPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

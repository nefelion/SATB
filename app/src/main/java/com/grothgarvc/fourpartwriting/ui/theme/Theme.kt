package com.grothgarvc.fourpartwriting.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One fixed light palette, so the toolbars match the paper-coloured score on every phone
private val PaperScheme = lightColorScheme(
    primary = InkBlue,
    onPrimary = Color.White,
    inversePrimary = Color(0xFFAFC6EE), // snackbar action
    primaryContainer = InkBlueSoft,
    onPrimaryContainer = Ink,
    secondary = InkSoft,
    secondaryContainer = PaperDeep,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = PaperDim,
    surfaceContainer = PaperDim,
    surfaceContainerHigh = PaperDim,
    surfaceContainerHighest = PaperDeep,
    surfaceTint = Color.Transparent,
    outline = Outline,
    outlineVariant = PaperDeep,
    error = ErrorRed,
    errorContainer = ErrorSoft,
    onErrorContainer = Ink,
)

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PaperScheme, typography = Typography, content = content)
}

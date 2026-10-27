package com.nefelion.fourpartwriting.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// One fixed palette per mode, so the toolbars match the score on every phone
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

private val NightScheme = darkColorScheme(
    primary = NightBlue,
    onPrimary = Night,
    inversePrimary = InkBlue,
    primaryContainer = NightBlueDeep,
    onPrimaryContainer = NightInk,
    secondary = NightInkSoft,
    secondaryContainer = NightDeep,
    onSecondaryContainer = NightInk,
    background = Night,
    onBackground = NightInk,
    surface = Night,
    onSurface = NightInk,
    surfaceVariant = NightDim,
    onSurfaceVariant = NightInkSoft,
    // Lowest = the raised "selected" chip in segmented toggles, so it is the lightest here
    surfaceContainerLowest = NightHigh,
    surfaceContainerLow = NightDim,
    surfaceContainer = NightDim,
    surfaceContainerHigh = NightDim,
    surfaceContainerHighest = NightDeep,
    surfaceTint = Color.Transparent,
    inverseSurface = NightInk,
    inverseOnSurface = Night,
    outline = NightOutline,
    outlineVariant = NightDeep,
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF5C2A27),
    onErrorContainer = NightInk,
)

/** Colors with no Material slot: voices, check results on notes and in the list, playback, non-chord tones. */
@Immutable
data class ScoreColors(
    /** Notes, staves and text in the score. */
    val ink: Color,
    val voices: List<Color>,
    val errorNote: Color, val warnNote: Color,
    val errorText: Color, val warnText: Color, val ok: Color,
    val play: Color, val nct: Color,
    /** Strength of the insertion caret band (a softer tint reads better on dark). */
    val caretAlpha: Float,
    /** Ink share (rest is paper) of notes faded around a picked check issue; 1 = plain ink. */
    val otherInk: Float,
)

private val PaperScore = ScoreColors(
    ink = Ink,
    voices = listOf(Color(0xFFC62828), Color(0xFFEF6C00), Color(0xFF2E7D32), Color(0xFF1565C0)),
    errorNote = Color(0xFFD32F2F), warnNote = Color(0xFFE67700),
    errorText = Color(0xFFC62828), warnText = Color(0xFFB85C00), ok = Color(0xFF2E7D32),
    play = Color(0xFF2E7D32), nct = Color(0xFF5E35B1), caretAlpha = 0.22f, otherInk = 0.65f,
)

// Lighter, slightly softened versions that stay readable on the dark background
private val NightScore = ScoreColors(
    // Dimmer than the UI text: full-strength light ink on charcoal glares in long sessions
    ink = Color(0xFFB3AC9F),
    voices = listOf(Color(0xFFE0605A), Color(0xFFE8923A), Color(0xFF5DB062), Color(0xFF5E92E6)),
    errorNote = Color(0xFFFF6E66), warnNote = Color(0xFFFFB347),
    errorText = Color(0xFFFF8A80), warnText = Color(0xFFFFB74D), ok = Color(0xFF81C784),
    play = Color(0xFF81C784), nct = Color(0xFFB39DDB), caretAlpha = 0.14f,
    otherInk = 1f,
)

val LocalScoreColors = staticCompositionLocalOf { PaperScore }

/** Follows the phone's dark mode setting. */
@Composable
fun MyApplicationTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScoreColors provides if (dark) NightScore else PaperScore) {
        MaterialTheme(colorScheme = if (dark) NightScheme else PaperScheme, typography = Typography, content = content)
    }
}

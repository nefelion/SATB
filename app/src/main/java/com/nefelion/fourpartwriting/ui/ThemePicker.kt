package com.nefelion.fourpartwriting.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nefelion.fourpartwriting.UiState
import com.nefelion.fourpartwriting.ui.theme.MyApplicationTheme

/**
 * First-run theme choice: the screen split in two (stacked in portrait, side by side in landscape),
 * each half drawn in its own theme with the score as a preview. Tapping a half picks it.
 */
@Composable
fun ThemePicker(st: UiState, onPick: (dark: Boolean) -> Unit) {
    val safe = WindowInsets.safeDrawing
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth > maxHeight
        val first = if (sideBySide) WindowInsetsSides.Left + WindowInsetsSides.Vertical else WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        val second = if (sideBySide) WindowInsetsSides.Right + WindowInsetsSides.Vertical else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
        if (sideBySide) Row(Modifier.fillMaxSize()) {
            ThemeHalf(st, dark = false, safe.only(first), { onPick(false) }, Modifier.weight(1f))
            ThemeHalf(st, dark = true, safe.only(second), { onPick(true) }, Modifier.weight(1f))
        } else Column(Modifier.fillMaxSize()) {
            ThemeHalf(st, dark = false, safe.only(first), { onPick(false) }, Modifier.weight(1f))
            ThemeHalf(st, dark = true, safe.only(second), { onPick(true) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThemeHalf(st: UiState, dark: Boolean, insets: WindowInsets, onPick: () -> Unit, modifier: Modifier) {
    val name = if (dark) "Dark" else "Light"
    MyApplicationTheme(dark) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
                Column(Modifier.fillMaxSize()) {
                    Text(name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp))
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        // Preview only: no caret, no selection; taps are taken by the overlay below
                        ScoreCanvas(st.copy(playing = true, selected = null), onTap = {}, onPress = {}, onNudge = {}, onDrag = {}, onTempoTap = {},
                            savedViewport = null, onViewport = { _, _ -> })
                    }
                    Text("Tap to use $name · you can change it in Settings", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, bottom = 20.dp, end = 24.dp))
                }
                // The whole half is the button, on top of the preview so its pan/zoom gestures do not take the tap
                Box(Modifier.matchParentSize().clickable(onClick = onPick).semantics { contentDescription = "Use the $name theme" })
            }
        }
    }
}

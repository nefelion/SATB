package com.nefelion.fourpartwriting

import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.nefelion.fourpartwriting.audio.Synth
import com.nefelion.fourpartwriting.ui.AppRoot
import com.nefelion.fourpartwriting.ui.ThemePicker
import com.nefelion.fourpartwriting.ui.Tutorial
import com.nefelion.fourpartwriting.ui.theme.MyApplicationTheme

private const val TOUR = 0
private const val PICK = 1
private const val APP = 2

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ask for the fastest refresh rate at the current resolution; some OEMs cap apps at 60 Hz otherwise.
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
        display.supportedModes
            .filter { it.physicalWidth == display.mode.physicalWidth && it.physicalHeight == display.mode.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?.let { m -> window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId } }
        barsFor(vm.state.value.dark ?: systemDark())
        // Landscape is full screen: no status or navigation bar cutting into the score.
        // A swipe from the edge shows them for a moment. (Rotation recreates the activity, so this runs again.)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        // Also draw beside the camera cutout on Android 9–14 (15+ always does for this targetSdk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        setContent {
            val st = vm.state.collectAsState()
            val chosen by remember { derivedStateOf { st.value.dark } }
            val dark = chosen ?: isSystemInDarkTheme()
            val seen by remember { derivedStateOf { st.value.tutorialSeen } }
            val vibration by remember { derivedStateOf { st.value.vibration } }
            // After the tutorial, the theme: the screen split into a light and a dark half (back returns to the tutorial's last page)
            var picking by rememberSaveable { mutableStateOf(false) }
            var back by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(dark, picking) {
                // Picker: light half on top / left, dark half below / right; bar icons suit each
                if (picking) enableEdgeToEdge(barStyle(false), barStyle(true)) else barsFor(dark)
            }
            BackHandler(enabled = picking) { picking = false; back = true }
            Crossfade(if (picking) PICK else if (!seen) TOUR else APP, label = "start") { k ->
                if (k == PICK) ThemePicker(st.value) { vm.setDark(it); picking = false; vm.finishTutorial() }
                else MyApplicationTheme(dark) {
                    // Vibration off: every haptic in the app goes to a no-op
                    CompositionLocalProvider(LocalHapticFeedback provides if (vibration) LocalHapticFeedback.current else NoHaptics) {
                        if (k == TOUR) Tutorial(atEnd = back, onDone = { picking = true }) else AppRoot(vm)
                    }
                }
            }
        }
    }

    private object NoHaptics : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
    }

    private fun systemDark() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Transparent system bars with icons that suit the chosen theme. */
    private fun barsFor(dark: Boolean) = enableEdgeToEdge(barStyle(dark), barStyle(dark))

    private fun barStyle(dark: Boolean): SystemBarStyle {
        val t = android.graphics.Color.TRANSPARENT
        return if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
    }

    // The audio stream only runs while the app is visible; a rotation keeps it, so playback goes on.
    override fun onStart() { super.onStart(); Synth.start() }
    override fun onStop() {
        vm.saveNow()
        if (!isChangingConfigurations) { vm.stop(); Synth.close() }
        super.onStop()
    }
}

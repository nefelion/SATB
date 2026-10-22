package com.grothgarvc.fourpartwriting

import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import com.grothgarvc.fourpartwriting.audio.Synth
import com.grothgarvc.fourpartwriting.ui.EditorScreen
import com.grothgarvc.fourpartwriting.ui.ThemePicker
import com.grothgarvc.fourpartwriting.ui.Tutorial
import com.grothgarvc.fourpartwriting.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        barsFor(vm.state.value.dark)
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
            val dark by remember { derivedStateOf { st.value.dark } }
            val chosen by remember { derivedStateOf { st.value.themeChosen } }
            val seen by remember { derivedStateOf { st.value.tutorialSeen } }
            LaunchedEffect(dark, chosen) {
                // Picker: light half on top / left, dark half below / right; bar icons suit each
                if (chosen) barsFor(dark) else enableEdgeToEdge(barStyle(false), barStyle(true))
            }
            if (!chosen) ThemePicker(st.value, vm::setDark)
            else MyApplicationTheme(dark) {
                if (!seen) Tutorial(vm::finishTutorial) else EditorScreen(vm)
            }
        }
    }

    /** Transparent system bars with icons that suit the chosen theme. */
    private fun barsFor(dark: Boolean) = enableEdgeToEdge(barStyle(dark), barStyle(dark))

    private fun barStyle(dark: Boolean): SystemBarStyle {
        val t = android.graphics.Color.TRANSPARENT
        return if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
    }

    // The audio stream only runs while the app is visible.
    override fun onStart() { super.onStart(); Synth.start() }
    override fun onStop() { vm.saveNow(); Synth.close(); super.onStop() }
}

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
import com.grothgarvc.fourpartwriting.audio.Synth
import com.grothgarvc.fourpartwriting.ui.EditorScreen
import com.grothgarvc.fourpartwriting.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light bars always: the app has one light palette
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
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
            MyApplicationTheme {
                EditorScreen(vm)
            }
        }
    }

    // The audio stream only runs while the app is visible.
    override fun onStart() { super.onStart(); Synth.start() }
    override fun onStop() { vm.saveNow(); Synth.close(); super.onStop() }
}

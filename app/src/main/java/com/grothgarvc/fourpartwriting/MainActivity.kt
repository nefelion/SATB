package com.grothgarvc.fourpartwriting

import android.os.Bundle
import android.view.WindowManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.grothgarvc.fourpartwriting.audio.Synth
import com.grothgarvc.fourpartwriting.ui.EditorScreen
import com.grothgarvc.fourpartwriting.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

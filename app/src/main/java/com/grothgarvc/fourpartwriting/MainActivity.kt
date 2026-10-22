package com.grothgarvc.fourpartwriting

import android.os.Bundle
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

package com.grothgarvc.fourpartwriting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grothgarvc.fourpartwriting.audio.Synth
import com.grothgarvc.fourpartwriting.ui.EditorScreen
import com.grothgarvc.fourpartwriting.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                EditorScreen(viewModel())
            }
        }
    }

    // The audio stream only runs while the app is visible.
    override fun onStart() { super.onStart(); Synth.start() }
    override fun onStop() { Synth.close(); super.onStop() }
}

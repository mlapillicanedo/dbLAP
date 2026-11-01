package com.example.dblap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.dblap.audio.SpectrumEngine
import com.example.dblap.ui.SpectrumScreen
import com.example.dblap.ui.theme.DbLAPTheme

class MainActivity : ComponentActivity() {

    private val engine = SpectrumEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DbLAPTheme {
                SpectrumScreen(
                    engine = engine,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onStop() {
        // Libera el micrófono cuando la app deja de estar visible.
        engine.stop()
        super.onStop()
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }
}

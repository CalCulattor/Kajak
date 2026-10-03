package pl.kajakapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pl.kajakapp.ui.KajakAppRoot
import pl.kajakapp.ui.KajakTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KajakTheme {
                KajakAppRoot()
            }
        }
    }
}

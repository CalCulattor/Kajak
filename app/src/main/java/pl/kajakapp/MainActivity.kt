package pl.kajakapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableLongStateOf
import pl.kajakapp.notify.Notifier
import pl.kajakapp.ui.KajakAppRoot
import pl.kajakapp.ui.KajakTheme

class MainActivity : ComponentActivity() {
    /** Spływ do otwarcia po stuknięciu w powiadomienie (-1 = brak). */
    private val openTrip = mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTrip.longValue = tripFrom(intent)
        setContent {
            KajakTheme {
                KajakAppRoot(openTripId = openTrip.longValue, onOpenTripHandled = { openTrip.longValue = -1L })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTrip.longValue = tripFrom(intent)
    }

    private fun tripFrom(intent: Intent?): Long = intent?.getLongExtra(Notifier.EXTRA_TRIP_ID, -1L) ?: -1L
}

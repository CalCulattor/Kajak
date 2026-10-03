@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pl.kajakapp.tracking.TrackingService
import pl.kajakapp.util.Fmt

/** Podgląd trasy na żywo. Zamknięcie ekranu nie przerywa nagrywania – działa usługa w tle. */
@Composable
fun RecordingScreen(onBack: () -> Unit, onFinished: (trackId: Long?) -> Unit) {
    val container = rememberContainer()
    val context = LocalContext.current
    val live by container.recorder.live.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var waited by remember { mutableStateOf(false) }

    // Ekran nie gaśnie, dopóki oglądasz podgląd (samo nagrywanie działa i tak).
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // Usługa potrzebuje chwili na start; po kilku sekundach bez trasy uznajemy, że nic nie jest nagrywane.
    LaunchedEffect(Unit) {
        delay(START_WAIT_MS)
        waited = true
    }
    // Reagujemy tylko na zakończenie, które nastąpiło po wejściu na ten ekran (i tylko raz).
    val baseline = remember { container.recorder.lastResult.value }
    val result by container.recorder.lastResult.collectAsStateWithLifecycle()
    var handled by remember { mutableStateOf(false) }
    LaunchedEffect(result) {
        val finished = result
        if (finished != null && finished != baseline && !handled) {
            handled = true
            if (finished.trackId == null) {
                Toast.makeText(context, "Trasa była zbyt krótka – nie zapisano.", Toast.LENGTH_LONG).show()
            }
            onFinished(finished.trackId)
        }
    }
    // Gdyby zapis się nie udał, nie zostajemy w nieskończoność na „Zapisuję…”.
    LaunchedEffect(stopping) {
        if (stopping) {
            delay(STOP_WAIT_MS)
            if (!handled) onBack()
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                windowInsets = NoInsets,
                title = { Text(live?.title ?: "Nagrywanie trasy", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz (nagrywanie trwa)")
                    }
                }
            )
        }
    ) { padding ->
        val track = live
        if (track == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                if (!waited || stopping) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(if (stopping) "Zapisuję trasę…" else "Uruchamiam nagrywanie…")
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Żadna trasa nie jest teraz nagrywana.")
                        Button(onClick = onBack) { Text("Wróć") }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!track.gpsEnabled) {
                    WarningCard("GPS jest wyłączony. Włącz lokalizację w telefonie, aby zapisywać trasę.")
                } else if (track.lastFixAt == null) {
                    WarningCard("Czekam na sygnał GPS… Wyjdź na otwartą przestrzeń.")
                } else if (now - track.lastFixAt > STALE_FIX_MS) {
                    WarningCard("Brak świeżego odczytu GPS (${Fmt.ageText(track.lastFixAt, now)}).")
                }

                Text(
                    Fmt.duration((now - track.startedAt).coerceAtLeast(0)),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("czas trasy", style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigStat("Dystans", Fmt.distance(track.distanceM), Modifier.weight(1f))
                    // Bez świeżego odczytu GPS pokazujemy 0, a nie ostatnią znaną prędkość.
                    val fresh = track.lastFixAt != null && now - track.lastFixAt <= SPEED_FRESH_MS
                    BigStat("Prędkość", Fmt.speed(if (fresh) track.speedKmh else 0.0), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigStat("Czas w ruchu", Fmt.duration(track.movingMs), Modifier.weight(1f))
                    BigStat("Maks. prędkość", Fmt.speed(track.maxSpeedKmh), Modifier.weight(1f))
                }
                val avg = if (track.movingMs > 0) track.distanceM / (track.movingMs / 1000.0) * 3.6 else 0.0
                BigStat("Średnia w ruchu", Fmt.speed(avg), Modifier.fillMaxWidth())
                Text(
                    "Odczyty GPS: ${track.readings}" +
                        (track.lastAccuracyM?.let { " · dokładność ok. ${it.toInt()} m" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = { confirmStop = true },
                    enabled = !stopping,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Zakończ trasę") }
                Text(
                    "Nagrywanie trwa także po zamknięciu tego ekranu lub wygaszeniu telefonu – " +
                        "zakończ je tutaj albo w powiadomieniu.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Zakończyć trasę?") },
            text = { Text("Trasa zostanie zapisana w historii i pokażę jej podsumowanie.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    stopping = true
                    TrackingService.stop(context)
                }) { Text("Zakończ i zapisz") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Wróć do trasy") } }
        )
    }
}

private const val START_WAIT_MS = 6_000L
private const val STOP_WAIT_MS = 20_000L
private const val STALE_FIX_MS = 30_000L
private const val SPEED_FRESH_MS = 10_000L

@Composable
private fun WarningCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

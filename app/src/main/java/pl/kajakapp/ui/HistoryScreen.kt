@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.data.db.TripEntity
import pl.kajakapp.tracking.TrackingService
import pl.kajakapp.util.Fmt

@Composable
fun HistoryScreen(
    onOpenTrack: (Long) -> Unit,
    onOpenRecording: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val container = rememberContainer()
    val context = LocalContext.current
    val vm: HistoryViewModel = viewModel(
        factory = VmFactory {
            HistoryViewModel(container.tracks, container.recorder, container.trips, container.settings)
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var showStart by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<Pair<String, TripEntity?>?>(null) }
    var pendingResume by remember { mutableStateOf(false) }
    // Po „Wznów” karta przerwanej trasy znika na czas uruchamiania usługi.
    var resuming by remember { mutableStateOf(false) }
    LaunchedEffect(resuming) {
        if (resuming) {
            delay(8_000)
            resuming = false
        }
    }

    fun startNow(request: Pair<String, TripEntity?>) {
        TrackingService.start(context, request.first, request.second?.id, request.second?.title)
        onOpenRecording()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val request = pendingStart
        val resume = pendingResume
        pendingStart = null
        pendingResume = false
        if (request != null || resume) {
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation(context)) {
                if (request != null) {
                    startNow(request)
                } else {
                    resuming = true
                    TrackingService.resume(context)
                    onOpenRecording()
                }
            } else {
                Toast.makeText(
                    context,
                    "Do nagrywania trasy potrzebna jest dokładna lokalizacja.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun requestStart(request: Pair<String, TripEntity?>?) {
        if (hasFineLocation(context)) {
            if (request != null) {
                startNow(request)
            } else {
                resuming = true
                TrackingService.resume(context)
                onOpenRecording()
            }
        } else {
            pendingStart = request
            pendingResume = request == null
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text("Historia spływów") },
                windowInsets = NoInsets,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ustawienia serwera")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val live = state.live
                val interrupted = if (resuming) null else state.interrupted
                when {
                    live != null -> OutlinedButton(onClick = onOpenRecording, modifier = Modifier.fillMaxWidth()) {
                        Text("Trasa w toku: ${live.title} – otwórz")
                    }
                    interrupted != null -> InterruptedCard(
                        track = interrupted,
                        onResume = { requestStart(null) },
                        onFinish = {
                            vm.finishInterrupted { id ->
                                // Gdyby usługa nadal działała, kończymy ją też.
                                TrackingService.stop(context)
                                if (id != null) onOpenTrack(id)
                                else Toast.makeText(context, "Trasa była zbyt krótka – nie zapisano.", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                    else -> Button(onClick = { showStart = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("  Rozpocznij trasę")
                    }
                }
            }
            if (state.tracks.isNotEmpty()) {
                item { TotalsCard(state.totals) }
            }
            if (state.tracks.isEmpty()) {
                item {
                    Text(
                        "Nie masz jeszcze nagranych tras. Dotknij „Rozpocznij trasę” przed wejściem na wodę – " +
                            "aplikacja zapisze przebieg spływu i pokaże dystans, czas, prędkość i postoje.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(state.tracks, key = { it.id }) { track ->
                    TrackCard(track, onClick = { onOpenTrack(track.id) })
                }
            }
        }
    }

    if (showStart) {
        StartTrackDialog(
            trips = state.trips,
            onDismiss = { showStart = false },
            onStart = { title, trip ->
                showStart = false
                requestStart(title to trip)
            }
        )
    }
}

private fun hasFineLocation(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun InterruptedCard(track: TrackEntity, onResume: () -> Unit, onFinish: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nagrywanie zostało przerwane", fontWeight = FontWeight.Bold)
            Text(
                "Trasa „${track.title}” (start ${Fmt.dateTime(track.startedAt)}) nie została zakończona – " +
                    "system mógł zamknąć aplikację. Możesz kontynuować nagrywanie albo zapisać to, co już jest.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("Wznów") }
                OutlinedButton(onClick = onFinish) { Text("Zakończ i zapisz") }
            }
        }
    }
}

@Composable
private fun TotalsCard(totals: HistoryTotals) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Twoje spływy w sumie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Liczba tras: ${totals.count}")
            Text("Przepłynięte: ${Fmt.distance(totals.distanceM)}")
            Text("Czas na wodzie: ${Fmt.duration(totals.elapsedMs)} (w ruchu ${Fmt.duration(totals.movingMs)})")
            Text("Średnia w ruchu: ${Fmt.speed(totals.avgMovingKmh)}")
            Text("Najdłuższa trasa: ${Fmt.distance(totals.longestM)}")
            Text("Najwyższa prędkość: ${Fmt.speed(totals.topSpeedKmh)}")
        }
    }
}

@Composable
private fun TrackCard(track: TrackEntity, onClick: () -> Unit) {
    val avg = if (track.elapsedMs > 0) track.distanceM / (track.elapsedMs / 1000.0) * 3.6 else 0.0
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(Fmt.dateTime(track.startedAt) + (track.tripTitle?.let { " · spływ: $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            Text(
                "${Fmt.distance(track.distanceM)} · ${Fmt.duration(track.elapsedMs)} · śr. ${Fmt.speed(avg)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun StartTrackDialog(
    trips: List<TripEntity>,
    onDismiss: () -> Unit,
    onStart: (title: String, trip: TripEntity?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var trip by remember { mutableStateOf<TripEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rozpocznij trasę") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 80) title = it },
                    label = { Text("Nazwa (opcjonalnie)") },
                    singleLine = true
                )
                if (trips.isNotEmpty()) {
                    Text("Spływ (opcjonalnie)", style = MaterialTheme.typography.bodySmall)
                    ChoiceButton(
                        selectedLabel = trip?.title ?: "Bez przypisania do spływu",
                        options = listOf<TripEntity?>(null) + trips,
                        optionLabel = { it?.title ?: "Bez przypisania do spływu" },
                        onSelected = { selected ->
                            trip = selected
                            if (title.isBlank() && selected != null) title = selected.title
                        }
                    )
                }
                Text(
                    "Aplikacja będzie zapisywać Twoją pozycję GPS także przy wygaszonym ekranie " +
                        "(w pasku powiadomień pojawi się informacja). Dane zostają na tym telefonie.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = { onStart(title, trip) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

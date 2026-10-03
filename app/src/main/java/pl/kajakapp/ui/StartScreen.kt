@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import pl.kajakapp.data.LiveTrack
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.data.db.TripEntity
import pl.kajakapp.tracking.TrackingService
import pl.kajakapp.util.Fmt

/** Ekran startowy: jedna, duża czynność – rozpoczęcie trasy – a pod nią ostatnia trasa i sumy. */
@Composable
fun StartScreen(
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

    fun resumeNow() {
        resuming = true
        TrackingService.resume(context)
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
                if (request != null) startNow(request) else resumeNow()
            } else {
                Toast.makeText(
                    context,
                    "Do nagrywania trasy potrzebna jest dokładna lokalizacja.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** [request] == null oznacza wznowienie przerwanej trasy. */
    fun requestStart(request: Pair<String, TripEntity?>?) {
        if (hasFineLocation(context)) {
            if (request != null) startNow(request) else resumeNow()
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
            AppTopBar(
                title = { Text("KajakApp", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Konto i ustawienia")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                val live = state.live
                val interrupted = if (resuming) null else state.interrupted
                when {
                    live != null -> LiveHero(live, onClick = onOpenRecording)
                    interrupted != null -> InterruptedCard(
                        track = interrupted,
                        onResume = { requestStart(null) },
                        onFinish = {
                            vm.finishInterrupted { id ->
                                // Gdyby usługa nadal działała, kończymy ją też.
                                TrackingService.stop(context)
                                if (id != null) {
                                    onOpenTrack(id)
                                } else {
                                    Toast.makeText(context, "Trasa była zbyt krótka – nie zapisano.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                    else -> StartHero(onClick = { showStart = true })
                }
            }

            val last = state.tracks.firstOrNull()
            if (last != null) {
                item { SectionTitle("Ostatnia trasa") }
                item { TrackRow(last, onClick = { onOpenTrack(last.id) }) }
                item { SectionTitle("W sumie") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile("Przepłynięte", Fmt.distance(state.totals.distanceM), Modifier.weight(1f))
                            StatTile("Czas na wodzie", Fmt.duration(state.totals.elapsedMs), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile("Trasy", state.totals.count.toString(), Modifier.weight(1f))
                            StatTile("Średnia w ruchu", Fmt.speed(state.totals.avgMovingKmh), Modifier.weight(1f))
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "Po pierwszej trasie zobaczysz tu jej podsumowanie i swoje statystyki.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
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

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** Jedyny mocny akcent w aplikacji: duży pomarańczowy przycisk startu. */
@Composable
private fun StartHero(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onTertiary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rozpocznij trasę", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Zapiszę przebieg spływu: dystans, czas i prędkość.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Trwające nagrywanie: czas i dystans na dużej, ciemnej karcie; dotknięcie otwiera pełny podgląd. */
@Composable
private fun LiveHero(live: LiveTrack, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val fresh = live.lastFixAt != null && now - live.lastFixAt <= 10_000L
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trasa w toku · ${live.title}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                Fmt.duration((now - live.startedAt).coerceAtLeast(0)),
                style = MaterialTheme.typography.displayMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column {
                    Text(Fmt.distance(live.distanceM), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("dystans", style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    Text(
                        Fmt.speed(if (fresh) live.speedKmh else 0.0),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text("prędkość", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                if (!live.gpsEnabled) "GPS jest wyłączony – włącz lokalizację." else "Dotknij, aby otworzyć podgląd i zakończyć trasę.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun InterruptedCard(track: TrackEntity, onResume: () -> Unit, onFinish: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nagrywanie zostało przerwane", style = MaterialTheme.typography.titleMedium)
            Text(
                "Trasa „${track.title}” (start ${Fmt.dateTime(track.startedAt)}) nie została zakończona – " +
                    "system mógł zamknąć aplikację. Wznów nagrywanie albo zapisz to, co już jest.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Wznów") }
                OutlinedButton(onClick = onFinish) { Text("Zakończ i zapisz") }
            }
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 80) title = it },
                    label = { Text("Nazwa (opcjonalnie)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
                    "Aplikacja zapisuje Twoją pozycję GPS także przy wygaszonym ekranie – w pasku powiadomień " +
                        "pojawi się informacja. Dane zostają na tym telefonie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onStart(title, trip) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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

/** Pytanie o uprawnienia przy wejściu na ekran zadajemy raz na uruchomienie aplikacji. */
private var askedAtEntry = false

private const val STARTING_WAIT_MS = 8_000L
private const val STOPPING_WAIT_MS = 20_000L
private const val STALE_FIX_MS = 30_000L
private const val SPEED_FRESH_MS = 10_000L

/**
 * Ekran startowy: mapa na całą stronę, a na dole jeden panel – przycisk „Rozpocznij trasę”,
 * który w trakcie nagrywania zamienia się w statystyki na żywo i przycisk „Zakończ”.
 */
@Composable
fun StartScreen(
    onOpenTrack: (Long) -> Unit,
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
    val livePath by container.recorder.livePath.collectAsStateWithLifecycle()
    val live = state.live

    var showStart by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<Pair<String, TripEntity?>?>(null) }
    var pendingResume by remember { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(hasAnyLocation(context)) }
    var recenter by remember { mutableIntStateOf(0) }
    var statsVisible by rememberSaveable { mutableStateOf(true) }
    // Po „Start”/„Wznów” usługa potrzebuje chwili; w tym czasie panel pokazuje „Uruchamiam…”.
    var starting by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(starting) {
        if (starting) {
            delay(STARTING_WAIT_MS)
            starting = false
        }
    }
    LaunchedEffect(live) { if (live != null) starting = false }
    LaunchedEffect(live != null) {
        while (live != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Ekran nie gaśnie, gdy trwa nagrywanie i patrzysz na mapę (samo nagrywanie działa i tak).
    val view = LocalView.current
    DisposableEffect(view, live != null) {
        view.keepScreenOn = live != null
        onDispose { view.keepScreenOn = false }
    }

    // Zakończenie trasy: reagujemy tylko na wynik, którego jeszcze nie obsłużyliśmy.
    val result by container.recorder.lastResult.collectAsStateWithLifecycle()
    var handledSeq by remember { mutableLongStateOf(container.recorder.lastResult.value?.seq ?: -1L) }
    LaunchedEffect(result) {
        val finished = result
        if (finished != null && finished.seq != handledSeq) {
            handledSeq = finished.seq
            stopping = false
            val id = finished.trackId
            if (id != null) {
                onOpenTrack(id)
            } else {
                Toast.makeText(context, "Trasa była zbyt krótka – nie zapisano.", Toast.LENGTH_LONG).show()
            }
        }
    }
    // Gdyby zapis się nie udał, nie zostajemy w nieskończoność na „Zapisuję…”.
    LaunchedEffect(stopping) {
        if (stopping) {
            delay(STOPPING_WAIT_MS)
            stopping = false
        }
    }

    fun startNow(request: Pair<String, TripEntity?>) {
        starting = true
        TrackingService.start(context, request.first, request.second?.id, request.second?.title)
    }

    fun resumeNow() {
        starting = true
        TrackingService.resume(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        locationGranted = hasAnyLocation(context)
        val request = pendingStart
        val resume = pendingResume
        pendingStart = null
        pendingResume = false
        if (request != null || resume) {
            if (hasFineLocation(context)) {
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

    // Pozycja na mapie wymaga lokalizacji – pytamy raz przy wejściu na ekran.
    LaunchedEffect(Unit) {
        if (!askedAtEntry && !hasAnyLocation(context)) {
            askedAtEntry = true
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            KajakMap(
                path = livePath,
                modifier = Modifier.fillMaxSize(),
                followUser = locationGranted,
                ornamentsOnTop = true,
                recenterKey = recenter
            )
            if (locationGranted) {
                SmallFloatingActionButton(
                    onClick = { recenter++ },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Default.Place, contentDescription = "Pokaż moją pozycję") }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val interrupted = if (starting) null else state.interrupted
                    when {
                        live != null -> LiveStats(
                            live = live,
                            now = now,
                            stopping = stopping,
                            statsVisible = statsVisible,
                            onToggleStats = { statsVisible = !statsVisible },
                            onTogglePause = { vm.setPaused(!live.paused) },
                            onStop = { confirmStop = true }
                        )
                        interrupted != null -> InterruptedCard(
                            track = interrupted,
                            onResume = { requestStart(null) },
                            onFinish = {
                                // Przejście do trasy (albo komunikat) obsługuje wspólny efekt wyniku powyżej.
                                vm.finishInterrupted {
                                    // Gdyby usługa nadal działała, kończymy ją też.
                                    TrackingService.stop(context)
                                }
                            }
                        )
                        else -> StartButton(starting = starting, onClick = { showStart = true })
                    }
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

@Composable
private fun StartButton(starting: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !starting,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
        Text(
            if (starting) "  Uruchamiam…" else "  Rozpocznij trasę",
            style = MaterialTheme.typography.titleLarge
        )
    }
}

/** Statystyki trasy w toku (można je schować) oraz przyciski „Wstrzymaj/Wznów” i „Zakończ”. */
@Composable
private fun LiveStats(
    live: LiveTrack,
    now: Long,
    stopping: Boolean,
    statsVisible: Boolean,
    onToggleStats: () -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit
) {
    val fresh = live.lastFixAt != null && now - live.lastFixAt <= SPEED_FRESH_MS
    val warning = when {
        live.paused -> null
        !live.gpsEnabled -> "GPS jest wyłączony – włącz lokalizację w telefonie."
        live.lastFixAt == null -> "Czekam na sygnał GPS… Wyjdź na otwartą przestrzeń."
        now - live.lastFixAt > STALE_FIX_MS -> "Brak świeżego odczytu GPS (${Fmt.ageText(live.lastFixAt, now)})."
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (live.paused) "${live.title}\twstrzymano" else live.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            TextButton(onClick = onToggleStats) { Text(if (statsVisible) "Ukryj statystyki" else "Pokaż statystyki") }
        }
        if (statsVisible) {
            Text(Fmt.duration(live.activeElapsedMs(now)), style = MaterialTheme.typography.displaySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Dystans", Fmt.distance(live.distanceM), Modifier.weight(1f))
                // Bez świeżego odczytu GPS (albo przy pauzie) pokazujemy 0, a nie ostatnią znaną prędkość.
                StatTile("Prędkość", Fmt.speed(if (fresh && !live.paused) live.speedKmh else 0.0), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Czas w ruchu", Fmt.duration(live.movingMs), Modifier.weight(1f))
                StatTile("Maks. prędkość", Fmt.speed(live.maxSpeedKmh), Modifier.weight(1f))
            }
        }
        if (warning != null) {
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onTogglePause,
                enabled = !stopping,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f).height(60.dp)
            ) {
                Text(if (live.paused) "Wznów" else "Wstrzymaj", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = onStop,
                enabled = !stopping,
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f).height(60.dp)
            ) {
                Text(if (stopping) "Zapisuję…" else "Zakończ", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun hasAnyLocation(context: Context): Boolean =
    hasFineLocation(context) || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

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

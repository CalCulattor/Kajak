@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.kajakapp.data.PersonOnMap
import pl.kajakapp.domain.PathPoint
import pl.kajakapp.data.LiveTrack
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.data.db.TripEntity
import pl.kajakapp.tracking.TrackingService
import pl.kajakapp.util.Fmt
import kotlin.math.abs
import kotlin.math.max

/** Pytanie o uprawnienia przy wejściu na ekran zadajemy raz na uruchomienie aplikacji. */
private var askedAtEntry = false

private const val PEOPLE_REFRESH_MS = 8_000L
private const val GPS_FRESH_MS = 15_000L
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
    var confirmHelp by remember { mutableStateOf(false) }
    var focusKey by remember { mutableIntStateOf(0) }
    var focusPoint by remember { mutableStateOf<PathPoint?>(null) }
    val scope = rememberCoroutineScope()
    val me by container.settings.session.collectAsStateWithLifecycle()
    val myName = me?.username

    // Spływ „aktualnie rozpoczęty”: ten, do którego nagrywasz trasę, a bez nagrywania – udostępniony na serwerze
    // spływ trwający teraz (służy tylko do wezwania pomocy i do alarmów innych osób). Pozycje uczestników
    // widać wyłącznie podczas nagrywania trasy w tym spływie i tylko osób z tego samego spływu.
    val contextTrip = pickContextTrip(state.trips, live?.tripId, System.currentTimeMillis())
    val sharingNow = live?.tripId != null && live.tripId == contextTrip?.id

    // Po powrocie do aplikacji odświeżamy pozycje od razu, a w tle nie odpytujemy serwera.
    var resumed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }

    var people by remember { mutableStateOf<List<PersonOnMap>>(emptyList()) }
    var peopleError by remember { mutableStateOf<String?>(null) }
    var peopleLoaded by remember { mutableStateOf(false) }
    suspend fun refreshPeople(tripId: Long) {
        val result = container.sync.fetchLocations(tripId)
        peopleError = result.error
        // Bez serwera (albo ze starym serwerem) pokazujemy chociaż wezwania pomocy znane z telefonu.
        people = if (result.error == null) result.people else container.sync.localHelpPeople(tripId)
        peopleLoaded = true
    }
    LaunchedEffect(contextTrip?.id, myName) {
        people = emptyList()
        peopleError = null
        peopleLoaded = false
    }
    LaunchedEffect(contextTrip?.id, myName, resumed) {
        val tripId = contextTrip?.id ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        while (true) {
            refreshPeople(tripId)
            delay(PEOPLE_REFRESH_MS)
        }
    }

    // Własną pozycję wysyła usługa nagrywania (co ok. 10 s) tylko, gdy trasa jest przypisana do spływu,
    // a po zakończeniu trasy przestaje ją udostępniać.
    val others = people.filter { !it.name.equals(myName, ignoreCase = true) }
    // Zwykłe pozycje tylko podczas nagrywania; wezwania pomocy widać zawsze.
    val mapPeople = if (sharingNow) others else others.filter { it.needsHelp }
    val helpers = others.filter { it.needsHelp }
    val myAlert = people.firstOrNull { it.needsHelp && it.name.equals(myName, ignoreCase = true) }

    val gpsStatus = rememberGpsStatus(active = locationGranted, recorderFixAt = live?.lastFixAt)

    val requestHelpLocation = rememberLocationRequester { point ->
        val trip = contextTrip
        val user = myName
        if (point == null) {
            Toast.makeText(context, "Nie udało się ustalić pozycji. Włącz lokalizację i spróbuj ponownie.", Toast.LENGTH_LONG).show()
        } else if (trip != null && user != null) {
            scope.launch {
                container.trips.checkIn(trip.id, user, point.lat, point.lon, point.fixAt, true)
                val outcome = container.sync.syncTrip(trip.id)
                val text = if (outcome.ok) "Wezwano pomoc – uczestnicy spływu dostali powiadomienie."
                else "Wezwanie zapisane na telefonie, ale nie wysłane: ${outcome.message}"
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                container.sync.pushLocation(trip.id, point.lat, point.lon, point.fixAt)
                refreshPeople(trip.id)
            }
        }
    }

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
                title = { Text("Eddy", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { confirmHelp = true }) {
                        LifebuoyIcon(Modifier.size(26.dp))
                    }
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
                recenterKey = recenter,
                people = mapPeople,
                focus = focusPoint,
                focusKey = focusKey
            )
            GpsStatusChip(gpsStatus, Modifier.align(Alignment.TopStart).padding(12.dp))
            if (locationGranted) {
                SmallFloatingActionButton(
                    onClick = { recenter++ },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Default.Place, contentDescription = "Pokaż moją pozycję") }
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            val problem = peopleError
            if (contextTrip != null && myName != null && problem != null) {
                InfoBanner("Uczestnicy: $problem")
            } else if (contextTrip != null && myName != null && sharingNow && peopleLoaded && others.isEmpty()) {
                InfoBanner("Nikt inny w spływie „${contextTrip.title}” nie udostępnia teraz pozycji.")
            }
            helpers.forEach { person ->
                HelpBanner(
                    text = "${person.name} wzywa pomocy!",
                    actionLabel = "Pokaż na mapie",
                    onAction = {
                        focusPoint = PathPoint(person.lat, person.lon)
                        focusKey++
                    }
                )
            }
            if (myAlert != null && contextTrip != null) {
                HelpBanner(
                    text = "Wezwałeś pomoc – uczestnicy spływu widzą Twoją pozycję.",
                    actionLabel = "Odwołaj",
                    onAction = {
                        scope.launch {
                            val outcome = container.sync.cancelHelpByServerId(contextTrip.id, myAlert.helpCheckInId)
                            if (!outcome.ok && outcome.message.isNotEmpty()) {
                                Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                            }
                            refreshPeople(contextTrip.id)
                        }
                    }
                )
            }
            // Panel trasy w toku można przesunąć palcem: w dół chowa statystyki, w górę pokazuje je.
            val dragThresholdPx = with(LocalDensity.current) { 28.dp.toPx() }
            val dragModifier = if (live != null) {
                Modifier.pointerInput(dragThresholdPx) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragCancel = { total = 0f },
                        onDragEnd = {
                            if (total > dragThresholdPx) statsVisible = false
                            else if (total < -dragThresholdPx) statsVisible = true
                        },
                        onVerticalDrag = { _, dy -> total += dy }
                    )
                }
            } else {
                Modifier
            }
            Surface(
                modifier = Modifier.fillMaxWidth().then(dragModifier),
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
    }

    if (confirmHelp) {
        val trip = contextTrip
        AlertDialog(
            onDismissRequest = { confirmHelp = false },
            title = { Text("Wezwać pomoc?") },
            text = {
                Text(
                    when {
                        myName == null -> "Zaloguj się (ikona ustawień), aby wzywać pomoc w spływie."
                        trip == null -> "Nie masz teraz spływu udostępnionego na serwerze, więc wezwanie nikogo nie powiadomi. " +
                            "W razie zagrożenia życia zadzwoń pod numer 112 (nad wodą także GOPR/WOPR: 601 100 300)."
                        else -> "Wszyscy uczestnicy spływu „${trip.title}” dostaną informację, że wzywasz pomocy, " +
                            "i zobaczą Twoją pozycję na mapie. W razie zagrożenia życia zadzwoń też pod numer 112."
                    }
                )
            },
            confirmButton = {
                if (myName != null && trip != null) {
                    TextButton(onClick = {
                        confirmHelp = false
                        requestHelpLocation()
                    }) { Text("Wezwij pomoc", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { confirmHelp = false }) { Text("OK") }
                }
            },
            dismissButton = {
                if (myName != null && trip != null) {
                    TextButton(onClick = { confirmHelp = false }) { Text("Anuluj") }
                }
            }
        )
    }

    if (showStart) {
        StartTrackDialog(
            // Spływy po terminie znikają z listy następnego dnia – tu też.
            trips = state.trips.filter { it.startDateUtcMillis >= Fmt.todayUtcMidnight() },
            defaultTrip = contextTrip,
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
        // Uchwyt: przeciągnij panel w dół/górę (albo stuknij), by schować lub pokazać statystyki.
        Box(
            Modifier.fillMaxWidth().clickable(onClick = onToggleStats).padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        Text(
            if (live.paused) "${live.title}\twstrzymano" else live.title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        AnimatedVisibility(visible = statsVisible) {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** Wybiera spływ, którego uczestników pokazujemy na mapie (patrz komentarz przy użyciu). */
private fun pickContextTrip(trips: List<TripEntity>, liveTripId: Long?, now: Long): TripEntity? {
    val shared = trips.filter { it.serverId != null }
    liveTripId?.let { id -> shared.firstOrNull { it.id == id }?.let { return it } }
    // Data spływu to północ UTC, a spływ może trwać kilka dni – okno jest szerokie z obu stron.
    val ongoing = shared.filter { isOngoing(it, now) }
    return ongoing.minByOrNull { abs(it.startDateUtcMillis - now) }
}

private fun isOngoing(trip: TripEntity, now: Long): Boolean {
    val day = 24L * 60 * 60 * 1000
    return trip.serverId != null && trip.startDateUtcMillis - day <= now && now <= trip.startDateUtcMillis + 4 * day
}

@Composable
private fun InfoBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class GpsStatus { OFF, SEARCHING, OK }

private fun isGpsProviderOn(context: Context): Boolean =
    (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        .isProviderEnabled(LocationManager.GPS_PROVIDER)

/** Sprawdza, czy telefon ma teraz sygnał GPS: nasłuchuje odczytów, gdy ekran jest widoczny. */
@SuppressLint("MissingPermission")
@Composable
private fun rememberGpsStatus(active: Boolean, recorderFixAt: Long?): GpsStatus {
    val context = LocalContext.current
    var providerOn by remember { mutableStateOf(isGpsProviderOn(context)) }
    var lastFix by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            providerOn = isGpsProviderOn(context)
            delay(2_000)
        }
    }
    // Nasłuch tylko, gdy ekran jest na wierzchu – w tle nie zużywamy baterii.
    LifecycleResumeEffect(active) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastFix = System.currentTimeMillis()
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Wymagane na starszych wersjach Androida")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        if (active) {
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                // Brak uprawnienia – status zostanie „szukam”.
            } catch (e: IllegalArgumentException) {
                // Brak dostawcy GPS.
            }
        }
        onPauseOrDispose { manager.removeUpdates(listener) }
    }
    val newest = max(lastFix, recorderFixAt ?: 0L)
    return when {
        !providerOn -> GpsStatus.OFF
        newest > 0 && now - newest <= GPS_FRESH_MS -> GpsStatus.OK
        else -> GpsStatus.SEARCHING
    }
}

/** Mały wskaźnik sygnału GPS na mapie (w miejscu skali). */
@Composable
private fun GpsStatusChip(status: GpsStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        GpsStatus.OK -> "GPS: jest sygnał" to Color(0xFF2E7D32)
        GpsStatus.SEARCHING -> "GPS: szukam sygnału" to Color(0xFFF9A825)
        GpsStatus.OFF -> "GPS wyłączony" to MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HelpBanner(text: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 4.dp
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onAction) { Text(actionLabel) }
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
    defaultTrip: TripEntity?,
    onDismiss: () -> Unit,
    onStart: (title: String, trip: TripEntity?) -> Unit
) {
    // Domyślnie trasa należy do spływu z mapy – dzięki temu inni uczestnicy widzą Twoją pozycję.
    var title by remember { mutableStateOf(defaultTrip?.title ?: "") }
    var trip by remember { mutableStateOf(defaultTrip) }
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
                        "pojawi się informacja. " +
                        if (trip?.serverId != null) "Uczestnicy tego spływu będą widzieć Twoją pozycję na mapie."
                        else "Trasa zostaje na tym telefonie (bez spływu z serwera nikt nie widzi Twojej pozycji).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onStart(title, trip) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}


/** Ikona koła ratunkowego (wezwanie pomocy): czerwono-biały pierścień. */
@Composable
private fun LifebuoyIcon(modifier: Modifier = Modifier) {
    val red = Color(0xFFD32F2F)
    Canvas(modifier) {
        val ring = size.minDimension * 0.26f
        val diameter = size.minDimension - ring
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val box = Size(diameter, diameter)
        drawArc(red, 0f, 360f, false, topLeft, box, style = Stroke(ring))
        // Cztery białe pasy na przekątnych.
        for (start in listOf(25f, 115f, 205f, 295f)) {
            drawArc(Color.White, start, 40f, false, topLeft, box, style = Stroke(ring))
        }
        val edge = size.minDimension * 0.05f
        drawCircle(red, radius = size.minDimension / 2f - edge / 2f, style = Stroke(edge))
        drawCircle(red, radius = size.minDimension / 2f - ring - edge / 2f, style = Stroke(edge))
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import pl.kajakapp.domain.DataFreshness
import pl.kajakapp.domain.ObstacleType
import pl.kajakapp.domain.WaterReading
import pl.kajakapp.domain.WeatherSnapshot
import pl.kajakapp.util.Fmt
import pl.kajakapp.util.openInMaps

private val PL: Locale = Locale.forLanguageTag("pl-PL")

private fun num1(value: Double): String = String.format(PL, "%.1f", value)

@Composable
fun SectionScreen(sectionId: Long, onBack: () -> Unit) {
    val container = rememberContainer()
    val vm: SectionViewModel = viewModel(
        key = "section_$sectionId",
        factory = VmFactory { SectionViewModel(sectionId, container.rivers, container.conditions, container.sync) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showStationDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var pendingReport by remember { mutableStateOf<Pair<ObstacleType, String>?>(null) }

    val requestLocation = rememberLocationRequester { point ->
        pendingReport?.let { (type, description) ->
            vm.reportObstacle(type, description, point)
            if (point == null) {
                vm.showMessage("Nie udało się ustalić pozycji – zgłoszenie dodano bez lokalizacji.")
            }
        }
        pendingReport = null
    }

    LaunchedEffect(state.message) {
        val text = state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.consumeMessage()
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    windowInsets = NoInsets,
                    title = { Text(state.section?.section?.name ?: "Odcinek", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::refresh, enabled = !state.refreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "Odśwież dane")
                        }
                    }
                )
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    ) { padding ->
        val section = state.section
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            section == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("Nie znaleziono odcinka.")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { RiskBanner(state.risk, Modifier.fillMaxWidth()) }

                item {
                    WaterCard(
                        water = state.water,
                        stale = state.waterStale,
                        hasStation = !section.section.stationName.isNullOrBlank(),
                        stationName = section.section.stationName,
                        onEditStation = { showStationDialog = true }
                    )
                }

                item { WeatherCard(state.weather, state.weatherStale) }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Odcinek",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Rzeka: ${section.riverName}")
                            Text("Trudność: ${section.section.difficulty.label}")
                            Text("Długość: ${num1(section.section.lengthKm)} km")
                            Text("Start: ${section.section.putIn}")
                            Text("Meta: ${section.section.takeOut}")
                            Text(section.section.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Przeszkody",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(onClick = { showReportDialog = true }) { Text("Zgłoś przeszkodę") }
                    }
                }

                if (state.obstacles.isEmpty()) {
                    item { Text("Brak zgłoszonych przeszkód.") }
                } else {
                    items(state.obstacles, key = { it.entity.id }) { item ->
                        val o = item.entity
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(o.type.label, fontWeight = FontWeight.Bold)
                                if (o.description.isNotBlank()) Text(o.description)
                                Text(
                                    "Zgłoszono ${Fmt.dateTime(o.reportedAt)} · potwierdzeń: ${o.confirmations}" +
                                        " · zgłoszeń usunięcia: ${o.removalVotes}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (item.stale) {
                                    Text(
                                        "Niezweryfikowane od ponad 30 dni – traktuj jako możliwe.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { vm.confirmObstacle(o.id) }) { Text("Nadal tu jest") }
                                    TextButton(onClick = { vm.voteObstacleRemoved(o.id) }) { Text("Już usunięte") }
                                    val lat = o.lat
                                    val lon = o.lon
                                    if (lat != null && lon != null) {
                                        TextButton(onClick = { openInMaps(context, lat, lon, o.type.label) }) {
                                            Text("Mapa")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStationDialog) {
        StationDialog(
            current = state.section?.section?.stationName.orEmpty(),
            onConfirm = {
                showStationDialog = false
                vm.setStation(it)
            },
            onDismiss = { showStationDialog = false }
        )
    }

    if (showReportDialog) {
        ReportObstacleDialog(
            onConfirm = { type, description, withLocation ->
                showReportDialog = false
                if (withLocation) {
                    pendingReport = type to description
                    requestLocation()
                } else {
                    vm.reportObstacle(type, description, null)
                }
            },
            onDismiss = { showReportDialog = false }
        )
    }
}

@Composable
private fun WaterCard(
    water: WaterReading?,
    stale: Boolean,
    hasStation: Boolean,
    stationName: String?,
    onEditStation: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Woda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                !hasStation -> Text("Do tego odcinka nie przypisano wodowskazu.")
                water == null -> Text("Brak danych z wodowskazu „$stationName”. Spróbuj odświeżyć.")
                else -> {
                    Text("Wodowskaz: ${water.stationName}")
                    Text("Stan wody: ${water.levelCm?.let { "$it cm" } ?: "brak"}")
                    water.flowM3s?.let { Text("Przepływ: ${num1(it)} m³/s") }
                    water.waterTempC?.let { Text("Temperatura wody: ${num1(it)} °C") }
                    if (water.warningCm != null || water.alarmCm != null) {
                        Text(
                            "Stan ostrzegawczy: ${water.warningCm ?: "–"} cm · alarmowy: ${water.alarmCm ?: "–"} cm",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Pomiar: ${water.levelMeasuredAt ?: "brak daty"} · pobrano ${Fmt.ageText(water.fetchedAt)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (stale) {
                        Text(
                            "Dane starsze niż ${DataFreshness.MAX_AGE_HOURS} godz. – nie użyto ich do oceny ryzyka.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            TextButton(onClick = onEditStation) {
                Text(if (hasStation) "Zmień wodowskaz" else "Ustaw wodowskaz")
            }
        }
    }
}

@Composable
private fun WeatherCard(weather: WeatherSnapshot?, stale: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pogoda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (weather == null || !weather.hasData) {
                Text("Brak danych pogodowych. Spróbuj odświeżyć.")
            } else {
                weather.airTempC?.let { Text("Temperatura powietrza: ${num1(it)} °C") }
                weather.windGustMs?.let {
                    Text("Porywy wiatru (dziś): ${num1(it)} m/s (${num1(it * 3.6)} km/h)")
                }
                weather.precipitationMm?.let { Text("Opady (dziś): ${num1(it)} mm") }
                if (weather.thunderstorm) Text("Prognozowane burze.", fontWeight = FontWeight.Bold)
                Text(
                    "Pobrano ${Fmt.ageText(weather.fetchedAt)}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (stale) {
                    Text(
                        "Prognoza starsza niż ${DataFreshness.MAX_AGE_HOURS} godz. – nie użyto jej do oceny ryzyka.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StationDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wodowskaz IMGW") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Wpisz nazwę stacji dokładnie tak, jak w danych IMGW (np. „Sromowce Wyżne”). " +
                        "Puste pole wyłącza wodowskaz dla tego odcinka.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Nazwa stacji") },
                    singleLine = true
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Zapisz") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

@Composable
private fun ReportObstacleDialog(
    onConfirm: (ObstacleType, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(ObstacleType.STRAINER) }
    var description by remember { mutableStateOf("") }
    var withLocation by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zgłoś przeszkodę") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton(
                    selectedLabel = type.label,
                    options = ObstacleType.entries,
                    optionLabel = { it.label },
                    onSelected = { type = it }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Opis (np. po której stronie rzeki)") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withLocation, onCheckedChange = { withLocation = it })
                    Text("Dołącz moją pozycję GPS")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(type, description, withLocation) }) { Text("Zgłoś") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

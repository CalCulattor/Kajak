@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import pl.kajakapp.data.db.SectionWithRiver
import pl.kajakapp.util.Fmt

@Composable
fun TripsScreen(onOpenTrip: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val container = rememberContainer()
    val vm: TripsViewModel = viewModel(
        factory = VmFactory { TripsViewModel(container.trips, container.rivers, container.sync, container.settings, container.live) }
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val sections by vm.sections.collectAsStateWithLifecycle()
    val join by vm.join.collectAsStateWithLifecycle()
    val username by vm.username.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }

    // Spływ znika z listy następnego dnia po terminie (dane zostają na telefonie). Dzień sprawdzamy co minutę,
    // żeby lista odświeżyła się też po północy przy otwartym ekranie.
    var today by remember { mutableLongStateOf(Fmt.todayUtcMidnight()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            today = Fmt.todayUtcMidnight()
        }
    }

    // Spływy usunięte przez organizatora lub opuszczone znikają z telefonu przy wejściu na listę.
    LaunchedEffect(Unit) { vm.refresh() }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.consumeMessage()
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                title = { Text("Spływy") },
                windowInsets = NoInsets,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ustawienia serwera")
                    }
                }
            )
        },
        floatingActionButton = {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Dodaj spływ")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Dołącz do spływu") },
                        onClick = {
                            menuOpen = false
                            vm.openJoin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Nowy spływ") },
                        onClick = {
                            menuOpen = false
                            showCreate = true
                        }
                    )
                }
            }
        }
    ) { padding ->
        val list = items?.filter { it.trip.startDateUtcMillis >= today }
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    "Nie masz nadchodzących spływów.\nDotknij +, aby zaplanować spływ albo dołączyć do istniejącego.",
                    modifier = Modifier.padding(32.dp)
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.trip.id }) { item ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenTrip(item.trip.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                item.trip.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                Fmt.utcDate(item.trip.startDateUtcMillis) + Fmt.timeSuffix(item.trip.startTime)
                            )
                            item.sectionLabel?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "Organizator: ${item.trip.ownerUsername ?: item.trip.organizer}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    join?.let { state ->
        JoinTripDialog(
            state = state,
            onJoin = { id -> vm.joinTrip(id, onJoined = onOpenTrip) },
            onDismiss = vm::closeJoin
        )
    }

    if (showCreate) {
        CreateTripDialog(
            accountName = username,
            sections = sections,
            onDismiss = { showCreate = false },
            onCreate = { title, sectionId, date, startTime, overnight, organizer ->
                showCreate = false
                vm.create(title, sectionId, date, startTime, overnight, organizer, onCreated = onOpenTrip)
            }
        )
    }
}

@Composable
private fun JoinTripDialog(
    state: JoinUiState,
    onJoin: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spływy na serwerze") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!state.loading && state.message == null && state.trips.isEmpty()) {
                    Text("Na serwerze nie ma jeszcze żadnych spływów.")
                }
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.trips, key = { it.id }) { trip ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(trip.title, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${trip.startDate}\tOrganizator: ${trip.organizer}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = { onJoin(trip.id) }, enabled = !state.loading) {
                                    Text(if (trip.alreadyJoined) "Otwórz" else "Dołącz")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } }
    )
}

@Composable
private fun CreateTripDialog(
    accountName: String?,
    sections: List<SectionWithRiver>,
    onDismiss: () -> Unit,
    onCreate: (title: String, sectionId: Long?, dateUtcMillis: Long, startTime: String, overnight: Boolean, organizer: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var organizer by remember { mutableStateOf("") }
    var sectionId by remember { mutableStateOf<Long?>(null) }
    val overnight = false // opcja noclegu usunięta z formularza
    var dateMillis by remember { mutableLongStateOf(Fmt.todayUtcMidnight()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf("") }
    var showTimePicker by remember { mutableStateOf(false) }
    // Dziś nie można wybrać godziny, która już minęła (data to dzień lokalny zapisany jako północ UTC).
    val timeInPast = startTime.isNotEmpty() && dateMillis == Fmt.todayUtcMidnight() &&
        startTime < java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }

    val selectedSectionLabel = sections.firstOrNull { it.section.id == sectionId }
        ?.let { "${it.riverName}: ${it.section.name}" }
        ?: "Bez wybranego odcinka"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowy spływ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 120) title = it },
                    label = { Text("Nazwa spływu") },
                    singleLine = true
                )
                if (accountName != null) {
                    // Zalogowany użytkownik jest organizatorem pod nazwą swojego konta.
                    Text("Organizator: $accountName", fontWeight = FontWeight.Medium)
                } else {
                    OutlinedTextField(
                        value = organizer,
                        onValueChange = { if (it.length <= 80) organizer = it },
                        label = { Text("Twoje imię (organizator)") },
                        singleLine = true
                    )
                }
                ChoiceButton(
                    selectedLabel = selectedSectionLabel,
                    options = listOf<SectionWithRiver?>(null) + sections,
                    optionLabel = { option ->
                        option?.let { "${it.riverName}: ${it.section.name}" } ?: "Bez wybranego odcinka"
                    },
                    onSelected = { sectionId = it?.section?.id }
                )
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text("Data: ${Fmt.utcDate(dateMillis)}")
                }
                OutlinedButton(onClick = { showTimePicker = true }) {
                    Text(if (startTime.isEmpty()) "Godzina: nie podano" else "Godzina: $startTime")
                }
                if (timeInPast) {
                    Text(
                        "Ta godzina już minęła – wybierz późniejszą albo inny dzień.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (accountName != null || organizer.isNotBlank()) &&
                    dateMillis >= Fmt.todayUtcMidnight() && !timeInPast,
                onClick = { onCreate(title, sectionId, dateMillis, startTime, overnight, organizer) }
            ) { Text("Utwórz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )

    if (showTimePicker) {
        val initial = remember {
            startTime.takeIf { it.length == 5 }?.let { it.substring(0, 2).toInt() to it.substring(3).toInt() }
                ?: (9 to 0)
        }
        val timeState = rememberTimePickerState(initialHour = initial.first, initialMinute = initial.second, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Godzina startu") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    startTime = "%02d:%02d".format(timeState.hour, timeState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (startTime.isNotEmpty()) {
                        TextButton(onClick = {
                            startTime = ""
                            showTimePicker = false
                        }) { Text("Usuń godzinę") }
                    }
                    TextButton(onClick = { showTimePicker = false }) { Text("Anuluj") }
                }
            }
        )
    }

    if (showDatePicker) {
        // Terminu spływu nie można ustawić w przeszłości.
        val today = remember { Fmt.todayUtcMidnight() }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateMillis,
            selectableDates = remember {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= today
                    override fun isSelectableYear(year: Int): Boolean =
                        year >= java.time.Instant.ofEpochMilli(today).atZone(java.time.ZoneOffset.UTC).year
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { dateMillis = it }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Anuluj") } }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

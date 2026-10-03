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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import pl.kajakapp.data.db.SectionWithRiver
import pl.kajakapp.util.Fmt

@Composable
fun TripsScreen(onOpenTrip: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val container = rememberContainer()
    val vm: TripsViewModel = viewModel(
        factory = VmFactory { TripsViewModel(container.trips, container.rivers, container.sync) }
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val sections by vm.sections.collectAsStateWithLifecycle()
    val join by vm.join.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = { Text("Spływy") },
                windowInsets = NoInsets,
                actions = {
                    TextButton(onClick = vm::openJoin) { Text("Dołącz") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ustawienia serwera")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nowy spływ")
            }
        }
    ) { padding ->
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    "Nie masz jeszcze spływów.\nDotknij +, aby zaplanować pierwszy.",
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
                                Fmt.utcDate(item.trip.startDateUtcMillis) +
                                    if (item.trip.overnight) " · z noclegiem" else " · jednodniowy"
                            )
                            item.sectionLabel?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
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
            sections = sections,
            onDismiss = { showCreate = false },
            onCreate = { title, sectionId, date, overnight, organizer ->
                showCreate = false
                vm.create(title, sectionId, date, overnight, organizer, onCreated = onOpenTrip)
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
                                        "${trip.startDate} · ${trip.organizer}",
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
    sections: List<SectionWithRiver>,
    onDismiss: () -> Unit,
    onCreate: (title: String, sectionId: Long?, dateUtcMillis: Long, overnight: Boolean, organizer: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var organizer by remember { mutableStateOf("") }
    var sectionId by remember { mutableStateOf<Long?>(null) }
    var overnight by remember { mutableStateOf(false) }
    var dateMillis by remember { mutableLongStateOf(Fmt.todayUtcMidnight()) }
    var showDatePicker by remember { mutableStateOf(false) }

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
                OutlinedTextField(
                    value = organizer,
                    onValueChange = { if (it.length <= 80) organizer = it },
                    label = { Text("Twoje imię (organizator)") },
                    singleLine = true
                )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = overnight, onCheckedChange = { overnight = it })
                    Text("  Nocleg przy rzece", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && organizer.isNotBlank(),
                onClick = { onCreate(title, sectionId, dateMillis, overnight, organizer) }
            ) { Text("Utwórz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
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

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pl.kajakapp.data.db.CheckInEntity
import pl.kajakapp.data.db.GearItemEntity
import pl.kajakapp.data.db.ParticipantEntity
import pl.kajakapp.util.Fmt
import pl.kajakapp.util.openInMaps

private val TAB_TITLES = listOf("Uczestnicy", "Wyposażenie", "Pozycje")

/** Po ilu minutach pozycja GPS jest oznaczana jako „stara” przy zameldowaniu. */
private const val OLD_FIX_MINUTES = 5L

@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val container = rememberContainer()
    val vm: TripDetailViewModel = viewModel(
        key = "trip_$tripId",
        factory = VmFactory { TripDetailViewModel(tripId, container.trips, container.rivers, container.sync, container.settings) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

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
                AppTopBar(
                    windowInsets = NoInsets,
                    title = { Text(state.trip?.title ?: "Spływ", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                        }
                    },
                    actions = {
                        if (state.trip != null) {
                            IconButton(onClick = vm::syncNow, enabled = !state.syncing) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = if (state.trip?.serverId == null) {
                                        "Udostępnij na serwerze"
                                    } else {
                                        "Synchronizuj z serwerem"
                                    }
                                )
                            }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = when {
                                        state.trip?.serverId == null -> "Usuń spływ"
                                        state.isOrganizer -> "Usuń lub opuść spływ"
                                        else -> "Opuść spływ"
                                    }
                                )
                            }
                        }
                    }
                )
                if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    ) { padding ->
        val trip = state.trip
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            trip == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("Nie znaleziono spływu.")
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                TripSummary(
                    shared = trip.serverId != null,
                    date = Fmt.utcDate(trip.startDateUtcMillis),
                    overnight = trip.overnight,
                    sectionLabel = state.sectionLabel,
                    participants = state.participants
                )
                TabRow(selectedTabIndex = tab) {
                    TAB_TITLES.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> ParticipantsTab(
                        participants = state.participants,
                        shared = trip.serverId != null,
                        currentUser = state.currentUser,
                        isOrganizer = state.isOrganizer,
                        onMakeOrganizer = vm::makeOrganizer,
                        onAdd = vm::addParticipant,
                        onUpdateMine = vm::updateMyData,
                        onRemove = vm::removeParticipant
                    )
                    1 -> GearTab(
                        gear = state.gear,
                        // W spływie na serwerze przypisać można tylko uczestników z kontami.
                        participants = if (trip.serverId != null) {
                            state.participants.filter { it.serverId != null }
                        } else {
                            state.participants
                        },
                        onAdd = vm::addGear,
                        onAddSuggested = vm::addSuggestedGear,
                        onPacked = vm::setGearPacked,
                        onAssign = vm::assignGear,
                        onRemove = vm::removeGear
                    )
                    else -> CheckInsTab(
                        defaultName = state.currentUser ?: trip.organizer,
                        lockedName = if (trip.serverId != null) state.currentUser else null,
                        participants = state.participants,
                        checkIns = state.checkIns,
                        onCheckIn = vm::checkIn,
                        onCancelHelp = vm::cancelHelp
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        val sharedTrip = state.trip?.serverId != null
        val organizer = state.isOrganizer
        // Zwykły uczestnik może tylko opuścić spływ (na serwerze). Organizator może go usunąć dla wszystkich,
        // a opuścić tylko wtedy, gdy zostaje inny organizator.
        val canLeave = sharedTrip && (!organizer || state.hasOtherOrganizer)
        val canDelete = !sharedTrip || organizer
        val soleOrganizer = sharedTrip && organizer && !state.hasOtherOrganizer && state.participants.size > 1
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (canDelete) "Usunąć spływ?" else "Opuścić spływ?") },
            text = {
                Text(
                    when {
                        !sharedTrip ->
                            "Zostaną usunięci uczestnicy, lista wyposażenia i zameldowania tego spływu."
                        !organizer ->
                            "Przestaniesz być uczestnikiem tego spływu, a on zniknie z Twojej listy. " +
                                "Pozostali uczestnicy nadal go widzą. Wymaga połączenia z serwerem."
                        state.hasOtherOrganizer ->
                            "Możesz usunąć spływ dla WSZYSTKICH uczestników albo go opuścić – wtedy spływ " +
                                "zostaje z pozostałymi organizatorami. Wymaga połączenia z serwerem."
                        soleOrganizer ->
                            "Jesteś jedynym organizatorem, więc możesz tylko usunąć spływ dla WSZYSTKICH " +
                                "uczestników. Żeby go opuścić, najpierw mianuj kolejnego organizatora " +
                                "(zakładka Uczestnicy). Wymaga połączenia z serwerem."
                        else ->
                            "Usuniesz spływ razem z listą wyposażenia i zameldowaniami. " +
                                "Wymaga połączenia z serwerem."
                    }
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (canLeave) {
                        TextButton(onClick = {
                            confirmDelete = false
                            vm.deleteTrip(leaveOnly = true, onDeleted = onBack)
                        }) { Text("Opuść spływ") }
                    }
                    if (canDelete) {
                        TextButton(onClick = {
                            confirmDelete = false
                            vm.deleteTrip(leaveOnly = false, onDeleted = onBack)
                        }) { Text(if (sharedTrip) "Usuń dla wszystkich" else "Usuń") }
                    }
                    TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") }
                }
            }
        )
    }
}

@Composable
private fun TripSummary(
    shared: Boolean,
    date: String,
    overnight: Boolean,
    sectionLabel: String?,
    participants: List<ParticipantEntity>
) {
    val seats = participants.sumOf { it.carSeats }
    val people = participants.size
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(date + if (overnight) "\tz noclegiem" else "\tjednodniowy", fontWeight = FontWeight.Medium)
        sectionLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            if (shared) {
                "Udostępniony na serwerze – zmiany innych osób pojawiają się na bieżąco."
            } else {
                "Tylko na tym telefonie – przycisk udostępniania wyśle spływ na serwer, aby dołączyła ekipa."
            },
            style = MaterialTheme.typography.bodySmall
        )
        val transport = when {
            seats == 0 -> "Transport: nikt jeszcze nie zgłosił auta."
            seats < people -> "Transport: $seats miejsc w autach dla $people osób – brakuje ${people - seats}."
            else -> "Transport: $seats miejsc w autach dla $people osób."
        }
        Text(
            transport,
            style = MaterialTheme.typography.bodySmall,
            color = if (seats < people) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        val needKayaks = participants.count { it.needsKayak }
        if (needKayaks > 0) {
            Text("Potrzebne kajaki do wypożyczenia: $needKayaks", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ParticipantsTab(
    participants: List<ParticipantEntity>,
    shared: Boolean,
    currentUser: String?,
    isOrganizer: Boolean,
    onMakeOrganizer: (Long) -> Unit,
    onAdd: (name: String, carSeats: Int, needsKayak: Boolean) -> Unit,
    onUpdateMine: (carSeats: Int, needsKayak: Boolean) -> Unit,
    onRemove: (Long) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val me = participants.firstOrNull { it.name.equals(currentUser, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            if (!shared) {
                OutlinedButton(onClick = { showDialog = true }) { Text("Dodaj uczestnika") }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "W spływie na serwerze każdy dodaje tylko siebie – inne osoby dołączają " +
                            "przez przycisk „Dołącz” na liście spływów.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (me != null) {
                        OutlinedButton(onClick = { showDialog = true }) { Text("Moje dane (auto, kajak)") }
                    }
                }
            }
        }
        // Organizatorzy zawsze na początku listy.
        items(participants.sortedByDescending { it.isOrganizer }, key = { it.id }) { p ->
            val isMe = shared && p.name.equals(currentUser, ignoreCase = true)
            val localOnly = shared && p.serverId == null && !isMe
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (isMe) " (Ty)" else "", fontWeight = FontWeight.Bold)
                        if (p.isOrganizer) {
                            Text(
                                "Organizator",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        val details = buildList {
                            if (p.carSeats > 0) add("auto: ${p.carSeats} miejsc")
                            if (p.needsKayak) add("potrzebuje kajaka")
                            if (localOnly) add("tylko na tym telefonie")
                        }
                        if (details.isNotEmpty()) {
                            Text(details.joinToString("\t"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (shared && isOrganizer && !p.isOrganizer && p.serverId != null) {
                        TextButton(onClick = { onMakeOrganizer(p.id) }) { Text("Mianuj organizatorem") }
                    }
                    // W spływie na serwerze usunąć można tylko wpis lokalny; siebie – przez „Opuść spływ”.
                    if (!shared || localOnly) {
                        IconButton(onClick = { onRemove(p.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Usuń uczestnika ${p.name}")
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        var name by remember { mutableStateOf("") }
        var seats by remember { mutableStateOf(me?.takeIf { shared }?.carSeats?.toString().orEmpty()) }
        var needsKayak by remember { mutableStateOf(me?.takeIf { shared }?.needsKayak ?: false) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (shared) "Moje dane" else "Nowy uczestnik") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!shared) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= 80) name = it },
                            label = { Text("Imię") },
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = seats,
                        onValueChange = { seats = it.filter(Char::isDigit).take(2) },
                        label = { Text("Miejsca w aucie (z kierowcą, 0 = brak auta)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = needsKayak, onCheckedChange = { needsKayak = it })
                        Text("  Potrzebuję kajaka")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = shared || name.isNotBlank(),
                    onClick = {
                        if (shared) {
                            onUpdateMine(seats.toIntOrNull() ?: 0, needsKayak)
                        } else {
                            onAdd(name, seats.toIntOrNull() ?: 0, needsKayak)
                        }
                        showDialog = false
                    }
                ) { Text(if (shared) "Zapisz" else "Dodaj") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun GearTab(
    gear: List<GearItemEntity>,
    participants: List<ParticipantEntity>,
    onAdd: (String) -> Unit,
    onAddSuggested: () -> Unit,
    onPacked: (Long, Boolean) -> Unit,
    onAssign: (Long, String?) -> Unit,
    onRemove: (Long) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val unassigned = "Nikt"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDialog = true }) { Text("Dodaj pozycję") }
                OutlinedButton(onClick = onAddSuggested) { Text("Dodaj propozycje") }
            }
        }
        if (gear.isEmpty()) {
            item { Text("Lista jest pusta. „Dodaj propozycje” wstawi podstawowy zestaw (z biwakowym, jeśli jest nocleg).") }
        }
        items(gear, key = { it.id }) { g ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = g.packed, onCheckedChange = { onPacked(g.id, it) })
                    Column(Modifier.weight(1f)) {
                        Text(g.name, fontWeight = FontWeight.Medium)
                        ChoiceButton(
                            selectedLabel = "Bierze: ${g.assignedTo ?: unassigned}",
                            options = listOf<String?>(null) + participants.map { it.name },
                            optionLabel = { it ?: unassigned },
                            onSelected = { onAssign(g.id, it) }
                        )
                    }
                    IconButton(onClick = { onRemove(g.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Usuń ${g.name}")
                    }
                }
            }
        }
    }

    if (showDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Nowa pozycja") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 80) name = it },
                    label = { Text("Nazwa (np. namiot 3-os.)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onAdd(name)
                        showDialog = false
                    }
                ) { Text("Dodaj") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun CheckInsTab(
    defaultName: String,
    lockedName: String?,
    participants: List<ParticipantEntity>,
    checkIns: List<CheckInEntity>,
    onCheckIn: (name: String, point: pl.kajakapp.util.GeoPoint, needsHelp: Boolean) -> Unit,
    onCancelHelp: (checkInId: Long) -> Unit
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val requestLocation = rememberLocationRequester { point ->
        val request = pending
        pending = null
        locating = false
        if (request != null) {
            if (point != null) {
                onCheckIn(request.first, point, request.second)
            } else {
                scope.launch {
                    snackbar.showSnackbar(
                        "Nie udało się ustalić pozycji. Sprawdź uprawnienia i włącz lokalizację."
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Zameldowanie zapisuje pozycję GPS na tym telefonie. Gdy spływ jest udostępniony " +
                            "na serwerze, pozycja trafia też do reszty grupy (pobierzesz je przyciskiem " +
                            "synchronizacji).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (locating) LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { showDialog = true }, enabled = !locating) {
                        Text("Zamelduj mnie / poproś o pomoc")
                    }
                }
            }
            if (checkIns.isEmpty()) {
                item { Text("Brak zameldowań.") }
            }
            items(checkIns, key = { it.id }) { c ->
                val colors = if (c.needsHelp) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    CardDefaults.cardColors()
                }
                Card(Modifier.fillMaxWidth(), colors = colors) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            (if (c.needsHelp) "POTRZEBUJE POMOCY: " else "") + c.personName,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Zameldowano ${Fmt.dateTime(c.createdAt)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        val fixAgeMinutes = (c.createdAt - c.fixAt) / 60_000L
                        if (fixAgeMinutes >= OLD_FIX_MINUTES) {
                            Text(
                                "Uwaga: pozycja GPS sprzed ${Fmt.ageText(c.fixAt, c.createdAt).removeSuffix(" temu")}.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row {
                            TextButton(onClick = { openInMaps(context, c.lat, c.lon, c.personName) }) {
                                Text("Pokaż na mapie")
                            }
                            // Wezwanie odwołać może tylko osoba, która je wysłała.
                            if (c.needsHelp && c.personName.equals(lockedName ?: defaultName, ignoreCase = true)) {
                                TextButton(onClick = { onCancelHelp(c.id) }) { Text("Odwołaj wezwanie") }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showDialog) {
        var name by remember { mutableStateOf(lockedName ?: defaultName) }
        var needsHelp by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Zameldowanie") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lockedName != null) {
                        // W spływie na serwerze można zameldować tylko siebie.
                        Text("Melduje się: $lockedName", fontWeight = FontWeight.Medium)
                    } else {
                        ChoiceButton(
                            selectedLabel = "Wybierz osobę",
                            options = participants.map { it.name },
                            optionLabel = { it },
                            onSelected = { name = it }
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= 80) name = it },
                            label = { Text("Kto się melduje") },
                            singleLine = true
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = needsHelp, onCheckedChange = { needsHelp = it })
                        Text("  Potrzebuję pomocy")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        showDialog = false
                        pending = name to needsHelp
                        locating = true
                        requestLocation()
                    }
                ) { Text("Zamelduj") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Anuluj") } }
        )
    }
}

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pl.kajakapp.data.SeedData
import pl.kajakapp.data.db.CheckInEntity
import pl.kajakapp.data.db.GearItemEntity
import pl.kajakapp.data.db.ParticipantEntity
import pl.kajakapp.domain.GearRules
import pl.kajakapp.util.Fmt
import pl.kajakapp.util.openInMaps

private val TAB_TITLES = listOf("Uczestnicy", "Wyposażenie")

/** Po ilu minutach pozycja GPS jest oznaczana jako „stara” przy zameldowaniu. */

@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val container = rememberContainer()
    val vm: TripDetailViewModel = viewModel(
        key = "trip_$tripId",
        factory = VmFactory { TripDetailViewModel(
                tripId, container.trips, container.rivers, container.sync, container.settings, container.conditions
            ) }
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
                    date = Fmt.utcDate(trip.startDateUtcMillis) + Fmt.timeSuffix(trip.startTime),
                    overnight = trip.overnight,
                    sectionLabel = state.sectionLabel,
                    participants = state.participants,
                    forecast = state.forecast
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
                        onRemove = vm::removeParticipant
                    )
                    else -> GearTab(
                        gear = state.gear,
                        participantCount = if (trip.serverId != null) {
                            state.participants.count { it.serverId != null }
                        } else {
                            state.participants.size
                        },
                        viewer = state.viewer,
                        isOrganizer = state.isOrganizer,
                        overnight = trip.overnight,
                        onAddItems = vm::addGearItems,
                        onToggleConfirmed = vm::toggleGearConfirmed,
                        onSetRequirement = vm::setGearRequirement,
                        onRemove = vm::removeGear
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
    participants: List<ParticipantEntity>,
    forecast: ForecastUi?
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(date, fontWeight = FontWeight.Medium)
        sectionLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TripForecastRow(forecast, Modifier.padding(vertical = 4.dp))
        Text(
            if (shared) {
                "Udostępniony na serwerze – zmiany innych osób pojawiają się na bieżąco."
            } else {
                "Tylko na tym telefonie – przycisk udostępniania wyśle spływ na serwer, aby dołączyła ekipa."
            },
            style = MaterialTheme.typography.bodySmall
        )
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
    onRemove: (Long) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

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
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Nowy uczestnik") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 80) name = it },
                    label = { Text("Imię") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onAdd(name, 0, false)
                        showDialog = false
                    }
                ) { Text("Dodaj") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun GearTab(
    gear: List<GearItemEntity>,
    participantCount: Int,
    viewer: String,
    isOrganizer: Boolean,
    overnight: Boolean,
    onAddItems: (List<Pair<String, String>>) -> Unit,
    onToggleConfirmed: (Long) -> Unit,
    onSetRequirement: (Long, String) -> Unit,
    onRemove: (Long) -> Unit
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    // Wybór organizatora: nazwa pozycji -> „required” / „recommended”.
    val selected = remember { mutableStateMapOf<String, String>() }

    val existing = gear.map { it.name.trim().lowercase() }.toSet()
    val presets = (SeedData.baseGear + if (overnight) SeedData.overnightGear else emptyList())
        .filter { it.lowercase() !in existing }
    val mine = gear.count { GearRules.isConfirmed(it.confirmedBy, viewer) }
    val missingRequired = gear.count {
        it.requirement == GearRules.REQUIRED && !GearRules.isConfirmed(it.confirmedBy, viewer)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isOrganizer) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Wybierz wyposażenie",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Zaznacz elementy, które mają zabrać uczestnicy, i określ, czy są wymagane, czy tylko zalecane.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (presets.isEmpty()) {
                            Text(
                                "Wszystkie podstawowe elementy są już na liście.",
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        presets.forEach { name ->
                            val requirement = selected[name]
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (requirement == null) {
                                                selected[name] = if (name in SeedData.requiredGearByDefault) {
                                                    GearRules.REQUIRED
                                                } else {
                                                    GearRules.RECOMMENDED
                                                }
                                            } else {
                                                selected.remove(name)
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = requirement != null,
                                        onCheckedChange = null,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                    Text(name, fontWeight = FontWeight.Medium)
                                }
                                if (requirement != null) {
                                    RequirementChips(
                                        requirement = requirement,
                                        onSelected = { selected[name] = it },
                                        modifier = Modifier.padding(start = 48.dp, bottom = 4.dp)
                                    )
                                }
                            }
                        }
                        PrimaryAction(
                            text = if (selected.isEmpty()) "Dodaj" else "Dodaj (${selected.size})",
                            onClick = {
                                // Zachowujemy kolejność z listy propozycji.
                                onAddItems(presets.filter { it in selected }.map { it to (selected[it] ?: GearRules.RECOMMENDED) })
                                selected.clear()
                            },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        TextButton(onClick = { showCustomDialog = true }) { Text("Dodaj własną pozycję") }
                    }
                }
            }
        }

        item {
            Text(
                "Wyposażenie spływu",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (gear.isEmpty()) {
            item {
                Text(
                    if (isOrganizer) {
                        "Lista jest pusta – zaznacz elementy powyżej i dotknij „Dodaj”."
                    } else {
                        "Organizator nie wybrał jeszcze wyposażenia."
                    }
                )
            }
        } else {
            item {
                Text(
                    "Masz $mine z ${gear.size}. Dotknij elementu, gdy go masz – zmieni kolor na zielony.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (missingRequired > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        items(gear, key = { it.id }) { g ->
            val confirmed = GearRules.isConfirmed(g.confirmedBy, viewer)
            val who = GearRules.decode(g.confirmedBy)
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onToggleConfirmed(g.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (confirmed) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (confirmed) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            ) {
                Row(
                    Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        if (confirmed) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = if (confirmed) "Masz ten element" else "Nie potwierdzono",
                        tint = if (confirmed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                    Column(Modifier.weight(1f)) {
                        Text(g.name, fontWeight = FontWeight.Medium)
                        Text(
                            GearRules.requirementLabel(g.requirement) +
                                if (participantCount > 0) "\tpotwierdzili: ${who.size}/$participantCount" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (who.isNotEmpty() && who.size <= 6) {
                            Text(who.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        }
                        if (isOrganizer) {
                            val other = if (g.requirement == GearRules.REQUIRED) GearRules.RECOMMENDED else GearRules.REQUIRED
                            TextButton(onClick = { onSetRequirement(g.id, other) }) {
                                Text("Zmień na: ${GearRules.requirementLabel(other).lowercase()}")
                            }
                        }
                    }
                    if (isOrganizer) {
                        IconButton(onClick = { onRemove(g.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Usuń ${g.name}")
                        }
                    }
                }
            }
        }
    }

    if (showCustomDialog) {
        var name by remember { mutableStateOf("") }
        var requirement by remember { mutableStateOf(GearRules.RECOMMENDED) }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Nowa pozycja") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 80) name = it },
                        label = { Text("Nazwa (np. namiot 3-os.)") },
                        singleLine = true
                    )
                    RequirementChips(requirement = requirement, onSelected = { requirement = it })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onAddItems(listOf(name to requirement))
                        showCustomDialog = false
                    }
                ) { Text("Dodaj") }
            },
            dismissButton = { TextButton(onClick = { showCustomDialog = false }) { Text("Anuluj") } }
        )
    }
}

/** Wybór „Wymagane” / „Zalecane” dla pozycji wyposażenia. */
@Composable
private fun RequirementChips(
    requirement: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = requirement == GearRules.REQUIRED,
            onClick = { onSelected(GearRules.REQUIRED) },
            label = { Text("Wymagane") }
        )
        FilterChip(
            selected = requirement != GearRules.REQUIRED,
            onClick = { onSelected(GearRules.RECOMMENDED) },
            label = { Text("Zalecane") }
        )
    }
}

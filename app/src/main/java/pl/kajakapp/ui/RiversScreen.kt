@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import pl.kajakapp.domain.RiverSearch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@Composable
fun RiversScreen(
    onOpenSection: (Long) -> Unit,
    onAddRoute: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val container = rememberContainer()
    val vm: RiversViewModel = viewModel(factory = VmFactory { RiversViewModel(container.rivers, container.sync) })
    val items by vm.items.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }

    // Przy wejściu na ekran (także po powrocie z formularza trasy) wyślij i pobierz trasy.
    LaunchedEffect(Unit) { vm.refresh(manual = false) }

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
            Column {
                AppTopBar(
                    title = { Text("Rzeki") },
                    windowInsets = NoInsets,
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Ustawienia serwera")
                        }
                    }
                )
                if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text("Szukaj rzeki lub odcinka") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Wyczyść")
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.extraLarge
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRoute) {
                Icon(Icons.Default.Add, contentDescription = "Dodaj trasę")
            }
        }
    ) { padding ->
        // Pasująca rzeka pokazuje wszystkie swoje odcinki; w pozostałych zostają tylko pasujące odcinki.
        val list = items?.mapNotNull { item ->
            if (query.isBlank()) return@mapNotNull item
            val riverHit = RiverSearch.matches(query, item.river.name, item.river.region)
            val hits = item.sections.filter {
                RiverSearch.matches(query, item.river.name, it.name, it.putIn, it.takeOut)
            }
            when {
                riverHit -> item
                hits.isNotEmpty() -> item.copy(sections = hits)
                else -> null
            }
        }
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(if (query.isBlank()) "Brak rzek w bazie." else "Nic nie znaleziono dla „${query.trim()}”.")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.river.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = item.river.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${item.river.type.label}\t${item.river.region}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(item.river.description, style = MaterialTheme.typography.bodyMedium)
                            item.sections.forEach { section ->
                                // Odcinek w ramce, żeby odróżnić go od nazwy rzeki.
                                Surface(
                                    onClick = { onOpenSection(section.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                                ) {
                                    Column(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(section.name, fontWeight = FontWeight.Medium)
                                        if (section.pendingSync) {
                                            Text(
                                                "Czeka na wysłanie na serwer",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Text(
                                            String.format(
                                                Locale.forLanguageTag("pl-PL"),
                                                "%.1f km\t%s",
                                                section.lengthKm,
                                                section.difficulty.label
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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

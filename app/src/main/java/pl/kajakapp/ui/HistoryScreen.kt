@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.util.Fmt

/** Lista nagranych tras (najnowsze na górze, pogrupowane miesiącami) z sumami na początku. */
@Composable
fun HistoryScreen(
    onOpenTrack: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val container = rememberContainer()
    val vm: HistoryViewModel = viewModel(
        factory = VmFactory {
            HistoryViewModel(container.tracks, container.recorder, container.trips, container.settings)
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            AppTopBar(
                title = { Text("Historia", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Konto i ustawienia")
                    }
                }
            )
        }
    ) { padding ->
        if (state.tracks.isEmpty()) {
            EmptyState(
                title = "Nie masz jeszcze tras",
                message = "Rozpocznij pierwszą trasę na zakładce Start. Tutaj zobaczysz każdy spływ " +
                    "z dystansem, czasem i prędkością.",
                modifier = Modifier.padding(padding)
            )
        } else {
            val groups = state.tracks.groupBy { Fmt.monthYear(it.startedAt) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile("Przepłynięte", Fmt.distance(state.totals.distanceM), Modifier.weight(1f))
                            StatTile("Czas na wodzie", Fmt.duration(state.totals.elapsedMs), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile("Najdłuższa trasa", Fmt.distance(state.totals.longestM), Modifier.weight(1f))
                            StatTile("Najwyższa prędkość", Fmt.speed(state.totals.topSpeedKmh), Modifier.weight(1f))
                        }
                    }
                }
                groups.forEach { (month, tracks) ->
                    item(key = "month_$month") { SectionTitle(month) }
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(track, onClick = { onOpenTrack(track.id) })
                    }
                }
            }
        }
    }
}

/** Wiersz trasy: nazwa i data po lewej, dystans i czas po prawej. */
@Composable
fun TrackRow(track: TrackEntity, onClick: () -> Unit) {
    val avg = if (track.elapsedMs > 0) track.distanceM / (track.elapsedMs / 1000.0) * 3.6 else 0.0
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    Fmt.dateTime(track.startedAt) + (track.tripTitle?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Fmt.distance(track.distanceM), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${Fmt.duration(track.elapsedMs)} · ${Fmt.speed(avg)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

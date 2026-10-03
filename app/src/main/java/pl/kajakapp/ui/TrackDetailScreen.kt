@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.domain.KmSplit
import pl.kajakapp.domain.PathPoint
import pl.kajakapp.domain.SpeedSample
import pl.kajakapp.domain.TrackSummary
import pl.kajakapp.util.Fmt

@Composable
fun TrackDetailScreen(trackId: Long, onBack: () -> Unit) {
    val container = rememberContainer()
    val context = LocalContext.current
    val vm: TrackDetailViewModel = viewModel(
        key = "track_$trackId",
        factory = VmFactory { TrackDetailViewModel(trackId, container.tracks) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val track = state.track
    val summary = state.summary

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            AppTopBar(
                windowInsets = NoInsets,
                title = { Text(track?.title ?: "Trasa", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    if (track != null) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Zmień nazwę")
                        }
                        if (summary != null && summary.hasData) {
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, track.title)
                                    .putExtra(Intent.EXTRA_TEXT, shareText(track, summary))
                                context.startActivity(Intent.createChooser(send, "Udostępnij podsumowanie"))
                            }) { Icon(Icons.Default.Share, contentDescription = "Udostępnij podsumowanie") }
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Usuń trasę")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            !state.loaded || (track != null && summary == null) -> Box(
                Modifier.fillMaxSize().padding(padding), Alignment.Center
            ) { CircularProgressIndicator() }
            track == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("Nie znaleziono trasy.")
            }
            summary == null || !summary.hasData -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), Alignment.Center) {
                Text("Ta trasa nie ma wystarczająco danych GPS do analizy.")
            }
            else -> Analysis(track, summary, Modifier.padding(padding))
        }
    }

    if (renaming && track != null) {
        var name by remember { mutableStateOf(track.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nazwa trasy") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 80) name = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.rename(name)
                    renaming = false
                }) { Text("Zapisz") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Anuluj") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć trasę?") },
            text = { Text("Trasa i jej punkty GPS zostaną usunięte z telefonu bez możliwości przywrócenia.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onDeleted = onBack)
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun Analysis(track: TrackEntity, s: TrackSummary, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            Fmt.dateTime(s.startedAt) + " – " + Fmt.clock(s.endedAt) +
                (track.tripTitle?.let { "\tspływ: $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium
        )

        val stats = listOf(
            "Dystans" to Fmt.distance(s.distanceM),
            "Czas całkowity" to Fmt.duration(s.elapsedMs),
            "Czas w ruchu" to Fmt.duration(s.movingMs),
            "Postoje (od 2 min)" to if (s.stops == 0) "brak" else "${s.stops}\trazem ${Fmt.duration(s.stoppedMs)}",
            "Średnia (całość)" to Fmt.speed(s.avgSpeedKmh),
            "Średnia w ruchu" to Fmt.speed(s.avgMovingSpeedKmh),
            "Maks. prędkość" to Fmt.speed(s.maxSpeedKmh),
            "Tempo w ruchu" to Fmt.pace(s.paceMinPerKm)
        )
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }

        Section("Przebieg trasy") {
            TrackMap(s.path, Modifier.fillMaxWidth().height(260.dp))
            Text(
                "Zielony punkt – start, czerwony – koniec. Ślad bez podkładu mapy (działa offline).",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (s.speedProfile.size >= 2) {
            Section("Prędkość w czasie") {
                SpeedChart(s.speedProfile, s.elapsedMs, s.avgMovingSpeedKmh, Modifier.fillMaxWidth().height(160.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0:00", style = MaterialTheme.typography.bodySmall)
                    Text("linia przerywana – średnia w ruchu", style = MaterialTheme.typography.bodySmall)
                    Text(Fmt.duration(s.elapsedMs), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (s.splits.isNotEmpty()) {
            Section("Kolejne kilometry") {
                SplitsTable(s.splits)
            }
        }

        Text(
            "Analiza pochodzi z ${s.usedPoints} odczytów GPS" +
                (if (s.droppedPoints > 0) " (odrzucono ${s.droppedPoints} niedokładnych)" else "") +
                ". Wartości są szacunkowe – GPS ma błąd rzędu kilku metrów.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Rysuje ślad GPS w prostym rzucie (z poprawką na zbieżność południków), bez podkładu mapy. */
@Composable
private fun TrackMap(path: List<PathPoint>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.background(bg)) {
        if (path.size < 2) return@Canvas
        val minLat = path.minOf { it.lat }
        val maxLat = path.maxOf { it.lat }
        val minLon = path.minOf { it.lon }
        val maxLon = path.maxOf { it.lon }
        val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = max((maxLon - minLon) * lonScale, 1e-9)
        val spanY = max(maxLat - minLat, 1e-9)
        val pad = 24.dp.toPx()
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        fun point(p: PathPoint) = Offset(
            (offX + (p.lon - minLon) * lonScale * scale).toFloat(),
            (offY + (maxLat - p.lat) * scale).toFloat()
        )
        val route = Path()
        path.forEachIndexed { i, p ->
            val o = point(p)
            if (i == 0) route.moveTo(o.x, o.y) else route.lineTo(o.x, o.y)
        }
        drawPath(route, line, style = Stroke(width = 4.dp.toPx()))
        drawCircle(Color(0xFF2E7D32), radius = 7.dp.toPx(), center = point(path.first()))
        drawCircle(Color(0xFFC62828), radius = 7.dp.toPx(), center = point(path.last()))
    }
}

@Composable
private fun SpeedChart(
    profile: List<SpeedSample>,
    elapsedMs: Long,
    avgMovingKmh: Double,
    modifier: Modifier
) {
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.18f)
    val avgColor = MaterialTheme.colorScheme.tertiary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val top = max(profile.maxOf { it.speedKmh }, avgMovingKmh).coerceAtLeast(1.0) * 1.1
    Column(modifier) {
        Text("maks. ${Fmt.speed(top)}", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().weight(1f).background(bg)) {
            val total = max(elapsedMs, 1L).toFloat()
            fun x(ms: Long) = ms / total * size.width
            fun y(kmh: Double) = (size.height - (kmh / top * size.height)).toFloat()
            val area = Path()
            val stroke = Path()
            profile.forEachIndexed { i, sample ->
                val px = x(sample.offsetMs)
                val py = y(sample.speedKmh)
                if (i == 0) {
                    area.moveTo(px, size.height)
                    area.lineTo(px, py)
                    stroke.moveTo(px, py)
                } else {
                    area.lineTo(px, py)
                    stroke.lineTo(px, py)
                }
            }
            area.lineTo(x(profile.last().offsetMs), size.height)
            area.close()
            drawPath(area, fill)
            drawPath(stroke, line, style = Stroke(width = 3.dp.toPx()))
            val avgY = y(avgMovingKmh)
            drawLine(
                avgColor, Offset(0f, avgY), Offset(size.width, avgY),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))
            )
        }
    }
}

@Composable
private fun SplitsTable(splits: List<KmSplit>) {
    val fastest = splits.filter { !it.isPartial }.maxByOrNull { it.speedKmh }
    val slowest = splits.filter { !it.isPartial }.minByOrNull { it.speedKmh }
    val topSpeed = splits.maxOf { it.speedKmh }.coerceAtLeast(0.1)
    val bar = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        splits.forEach { split ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (split.isPartial) "${Fmt.distance(split.distanceM)}" else "km ${split.index}",
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth((split.speedKmh / topSpeed).toFloat().coerceIn(0.02f, 1f))
                            .height(14.dp)
                            .background(bar.copy(alpha = if (split.isPartial) 0.45f else 0.8f))
                    )
                }
                Text(
                    "${Fmt.duration(split.durationMs)}\t${Fmt.speed(split.speedKmh)}",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (fastest != null && slowest != null && fastest.index != slowest.index) {
            Text(
                "Najszybszy: km ${fastest.index} (${Fmt.speed(fastest.speedKmh)}), " +
                    "najwolniejszy: km ${slowest.index} (${Fmt.speed(slowest.speedKmh)}).",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun shareText(track: TrackEntity, s: TrackSummary): String = buildString {
    appendLine("${track.title} – ${Fmt.dateTime(s.startedAt)}")
    appendLine("Dystans: ${Fmt.distance(s.distanceM)}")
    appendLine("Czas całkowity: ${Fmt.duration(s.elapsedMs)} (w ruchu ${Fmt.duration(s.movingMs)})")
    appendLine("Średnia: ${Fmt.speed(s.avgSpeedKmh)}, w ruchu ${Fmt.speed(s.avgMovingSpeedKmh)}")
    appendLine("Maks. prędkość: ${Fmt.speed(s.maxSpeedKmh)}")
    if (s.stops > 0) appendLine("Postoje: ${s.stops} (razem ${Fmt.duration(s.stoppedMs)})")
    append("Zapisano w KajakApp")
}

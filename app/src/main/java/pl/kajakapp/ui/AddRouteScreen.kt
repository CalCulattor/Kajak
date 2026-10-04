@file:OptIn(ExperimentalMaterial3Api::class)

package pl.kajakapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import pl.kajakapp.domain.Difficulty
import pl.kajakapp.domain.RiverType

// Limity zgodne z walidacją serwera, żeby formularz nie przyjął danych, które serwer odrzuci.
private const val MAX_NAME = 80
private const val MAX_SECTION_NAME = 120
private const val MAX_POINT = 120
private const val MAX_DESCRIPTION = 1000
private const val MAX_LENGTH_KM = 1000.0

private fun parseNumber(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

@Composable
fun AddRouteScreen(onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val container = rememberContainer()
    val vm: AddRouteViewModel = viewModel(factory = VmFactory { AddRouteViewModel(container.rivers) })

    var riverName by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var typeIndex by rememberSaveable { mutableIntStateOf(0) }
    var sectionName by rememberSaveable { mutableStateOf("") }
    var lengthText by rememberSaveable { mutableStateOf("") }
    var difficultyIndex by rememberSaveable { mutableIntStateOf(0) }
    var putIn by rememberSaveable { mutableStateOf("") }
    var takeOut by rememberSaveable { mutableStateOf("") }
    var latText by rememberSaveable { mutableStateOf("") }
    var lonText by rememberSaveable { mutableStateOf("") }
    var station by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }

    val requestLocation = rememberLocationRequester { point ->
        if (point == null) {
            error = "Nie udało się ustalić pozycji. Wpisz współrzędne ręcznie."
        } else {
            latText = String.format(Locale.US, "%.5f", point.lat)
            lonText = String.format(Locale.US, "%.5f", point.lon)
            error = null
        }
    }

    val riverType = RiverType.entries[typeIndex.coerceIn(0, RiverType.entries.lastIndex)]
    val difficulty = Difficulty.entries[difficultyIndex.coerceIn(0, Difficulty.entries.lastIndex)]

    fun submit() {
        val length = parseNumber(lengthText)
        val lat = parseNumber(latText)
        val lon = parseNumber(lonText)
        error = when {
            riverName.isBlank() -> "Podaj nazwę rzeki."
            sectionName.isBlank() -> "Podaj nazwę trasy (odcinka)."
            length == null || length < 0 || length > MAX_LENGTH_KM ->
                "Długość musi być liczbą od 0 do 1000 km."
            lat == null || lat < -90 || lat > 90 -> "Szerokość geograficzna musi być liczbą od -90 do 90."
            lon == null || lon < -180 || lon > 180 -> "Długość geograficzna musi być liczbą od -180 do 180."
            else -> null
        }
        if (error != null || length == null || lat == null || lon == null) return
        saving = true
        vm.save(
            riverName = riverName,
            region = region,
            riverType = riverType,
            sectionName = sectionName,
            lengthKm = length,
            difficulty = difficulty,
            putIn = putIn,
            takeOut = takeOut,
            lat = lat,
            lon = lon,
            stationName = station,
            description = description,
            onSaved = onSaved
        )
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            AppTopBar(
                windowInsets = NoInsets,
                title = { Text("Nowa trasa") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Trasa zapisze się na telefonie i – gdy jest połączenie – zostanie wysłana na serwer, " +
                    "żeby widzieli ją inni.",
                style = MaterialTheme.typography.bodySmall
            )
            LimitedField(riverName, { riverName = it }, "Rzeka", MAX_NAME)
            LimitedField(region, { region = it }, "Region (np. Małopolska)", MAX_NAME)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton(
                    selectedLabel = "Typ: ${riverType.label}",
                    options = RiverType.entries,
                    optionLabel = { it.label },
                    onSelected = { typeIndex = it.ordinal }
                )
                ChoiceButton(
                    selectedLabel = difficulty.label,
                    options = Difficulty.entries,
                    optionLabel = { it.label },
                    onSelected = { difficultyIndex = it.ordinal }
                )
            }
            LimitedField(sectionName, { sectionName = it }, "Nazwa trasy (np. Sromowce – Szczawnica)", MAX_SECTION_NAME)
            LimitedField(
                lengthText, { lengthText = it }, "Długość (km)", 12,
                keyboardType = KeyboardType.Decimal
            )
            LimitedField(putIn, { putIn = it }, "Start (miejsce wodowania)", MAX_POINT)
            LimitedField(takeOut, { takeOut = it }, "Meta (miejsce wyjęcia)", MAX_POINT)
            Text(
                "Współrzędne startu – służą do pobrania pogody.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LimitedField(
                    latText, { latText = it }, "Szer. geogr.", 12,
                    keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                )
                LimitedField(
                    lonText, { lonText = it }, "Dł. geogr.", 12,
                    keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                )
            }
            OutlinedButton(onClick = requestLocation) { Text("Użyj mojej pozycji") }
            LimitedField(station, { station = it }, "Wodowskaz IMGW (opcjonalnie)", MAX_NAME)
            LimitedField(description, { description = it }, "Opis (opcjonalnie)", MAX_DESCRIPTION, singleLine = false)

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            PrimaryAction(
                text = if (saving) "Zapisuję…" else "Zapisz trasę",
                onClick = { submit() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LimitedField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onChange(it) },
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth()
    )
}

package pl.kajakapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.kajakapp.domain.ForecastWindow
import pl.kajakapp.domain.RiskLevel
import pl.kajakapp.domain.WeatherSnapshot

private val PL_LOCALE: Locale = Locale.forLanguageTag("pl-PL")
private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd.MM", PL_LOCALE)

/** Pierwsze godziny dnia, w których planuje się spływ. */
private val HOURS = (5..21).toList()

private fun num1(value: Double): String = String.format(PL_LOCALE, "%.1f", value)

/** Skrót prognozy, np. "14,0 °C • porywy 6,2 m/s • opady 0,4 mm". */
fun weatherSummary(w: WeatherSnapshot): String = listOfNotNull(
    w.airTempC?.let { "${num1(it)} °C" },
    w.windGustMs?.let { "porywy ${num1(it)} m/s" },
    w.precipitationMm?.let { "opady ${num1(it)} mm" },
    if (w.thunderstorm) "burze" else null
).joinToString(" • ")

private fun dayLabel(day: LocalDate, index: Int): String {
    val prefix = when (index) {
        0 -> "Dziś"
        1 -> "Jutro"
        else -> null
    }
    val text = DAY_LABEL.format(day)
    return if (prefix != null) "$prefix ($text)" else text
}

/**
 * Karta „Sprawdź warunki na termin”: wybór dnia i godziny (do 16 dni naprzód) oraz ocena warunków
 * dla okna kilku godzin od wybranej godziny.
 */
@Composable
fun ForecastCheckerCard(
    forecast: ForecastUi?,
    onCheck: (LocalDate, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val days = remember { forecastDays() }
    var dayIndex by rememberSaveable { mutableIntStateOf(1) }
    var hour by rememberSaveable { mutableIntStateOf(10) }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Sprawdź warunki na termin",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Wybierz dzień i godzinę, żeby zaplanować spływ z wyprzedzeniem. Ocena obejmuje " +
                    "${ForecastWindow.DEFAULT_HOURS} godziny od wybranej godziny i opiera się na prognozie pogody.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceButton(
                    selectedLabel = dayLabel(days[dayIndex.coerceIn(0, days.lastIndex)], dayIndex),
                    options = days.indices.toList(),
                    optionLabel = { dayLabel(days[it], it) },
                    onSelected = { dayIndex = it }
                )
                ChoiceButton(
                    selectedLabel = "%02d:00".format(hour),
                    options = HOURS,
                    optionLabel = { "%02d:00".format(it) },
                    onSelected = { hour = it }
                )
            }
            PrimaryAction(
                text = "Sprawdź",
                onClick = { onCheck(days[dayIndex.coerceIn(0, days.lastIndex)], hour) },
                enabled = forecast?.loading != true,
                modifier = Modifier.fillMaxWidth()
            )
            if (forecast != null) {
                if (forecast.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!forecast.loading) {
                    forecast.whenText?.let { Text(it, fontWeight = FontWeight.Medium) }
                }
                forecast.weather?.let { Text(weatherSummary(it), style = MaterialTheme.typography.bodySmall) }
                forecast.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                forecast.risk?.let { RiskBanner(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** Ogólna ocena pogody na termin spływu – jedna linia ze znacznikiem koloru. */
@Composable
fun TripForecastRow(forecast: ForecastUi?, modifier: Modifier = Modifier) {
    if (forecast == null) return
    val level = forecast.risk?.level
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(riskColor(level ?: RiskLevel.UNKNOWN), CircleShape)
        )
        Column(Modifier.weight(1f)) {
            when {
                forecast.loading -> Text("Sprawdzam prognozę na termin spływu…", style = MaterialTheme.typography.bodySmall)
                level != null -> {
                    Text(
                        "Pogoda na termin spływu: ${level.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    forecast.weather?.let { Text(weatherSummary(it), style = MaterialTheme.typography.bodySmall) }
                }
                else -> Text(
                    forecast.message ?: "Brak prognozy na termin spływu.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

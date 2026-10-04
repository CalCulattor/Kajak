package pl.kajakapp.domain

import java.time.LocalDate
import java.time.LocalDateTime

/** Czy da się sprawdzić prognozę na dany termin. */
enum class ForecastRange { OK, PAST, TOO_FAR }

/**
 * Prognoza godzinowa (Open-Meteo) sprowadzona do jednego okna czasowego, np. 4 godzin od planowanego startu.
 * Czysta logika bez zależności od Androida, więc łatwa do testowania.
 */
object ForecastWindow {
    /** Domyślna długość okna – tyle mniej więcej trwa krótki spływ. */
    const val DEFAULT_HOURS = 4

    /** Open-Meteo podaje prognozę godzinową na 16 dni (dziś + 15). */
    const val MAX_DAYS_AHEAD = 15L

    private val THUNDERSTORM_CODES = setOf(95, 96, 99)

    fun range(start: LocalDateTime, now: LocalDateTime): ForecastRange {
        val today = now.toLocalDate()
        return when {
            start.toLocalDate().isBefore(today) -> ForecastRange.PAST
            start.toLocalDate().isAfter(today.plusDays(MAX_DAYS_AHEAD)) -> ForecastRange.TOO_FAR
            else -> ForecastRange.OK
        }
    }

    /**
     * Zbiera godziny od [start] (zaokrąglonego w dół do pełnej godziny) przez [hours] godzin.
     * Zwraca null, gdy w danych nie ma żadnej z tych godzin. [times] to lokalne czasy "2026-10-05T14:00".
     */
    fun summarize(
        times: List<String>,
        temperature: List<Double?>,
        precipitation: List<Double?>,
        gusts: List<Double?>,
        codes: List<Int?>,
        start: LocalDateTime,
        hours: Int,
        fetchedAt: Long
    ): WeatherSnapshot? {
        val from = start.withMinute(0).withSecond(0).withNano(0)
        val to = from.plusHours(hours.toLong())
        val picked = times.indices.filter { i ->
            val t = runCatching { LocalDateTime.parse(times[i]) }.getOrNull()
            t != null && !t.isBefore(from) && t.isBefore(to)
        }
        if (picked.isEmpty()) return null

        fun <T> List<T?>.at(i: Int): T? = getOrNull(i)

        val temps = picked.mapNotNull { temperature.at(it) }
        val rain = picked.mapNotNull { precipitation.at(it) }
        val gust = picked.mapNotNull { gusts.at(it) }
        val storm = picked.any { codes.at(it) in THUNDERSTORM_CODES }
        val snapshot = WeatherSnapshot(
            airTempC = temps.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it * 10) / 10.0 },
            windGustMs = gust.maxOrNull(),
            precipitationMm = rain.takeIf { it.isNotEmpty() }?.sum()?.let { Math.round(it * 10) / 10.0 },
            thunderstorm = storm,
            fetchedAt = fetchedAt,
            windowHours = hours
        )
        return snapshot.takeIf { it.hasData }
    }

    /** Dzień, od którego liczymy – pomocniczo dla UI (indeksy 0..[MAX_DAYS_AHEAD]). */
    fun days(today: LocalDate): List<LocalDate> = (0..MAX_DAYS_AHEAD).map { today.plusDays(it) }
}

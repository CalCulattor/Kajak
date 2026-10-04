package pl.kajakapp.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Fmt {
    /** Dopisek z godziną startu spływu (z tabulatorem jako separatorem) albo pusty tekst. */
    fun timeSuffix(startTime: String): String = if (startTime.isBlank()) "" else "\tgodz. $startTime"

    private val dateTimePattern = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    private val datePattern = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Czas lokalny urządzenia, np. "03.10 14:05". */
    fun dateTime(millis: Long): String =
        dateTimePattern.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

    /** Data zapisana jako północ UTC (format DatePickera), np. "03.10.2026". */
    fun utcDate(millis: Long): String =
        datePattern.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis))

    /** Dzisiejsza data (lokalna) jako północ UTC. */
    fun todayUtcMidnight(): Long =
        LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Czas trwania jako "1:05:09" albo "5:09". */
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Dystans: poniżej kilometra w metrach, dalej w kilometrach z dwoma miejscami. */
    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else "%.2f km".format(meters / 1000.0)

    fun speed(kmh: Double): String = "%.1f km/h".format(kmh)

    /** Tempo w minutach na kilometr, np. "11:07 min/km"; "–" gdy brak ruchu. */
    fun pace(minPerKm: Double?): String {
        if (minPerKm == null || minPerKm.isNaN() || minPerKm.isInfinite()) return "–"
        val totalSec = Math.round(minPerKm * 60).toInt()
        return "%d:%02d min/km".format(totalSec / 60, totalSec % 60)
    }

    private val clockPattern = DateTimeFormatter.ofPattern("HH:mm")

    private val monthPattern = DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.forLanguageTag("pl"))

    /** Miesiąc i rok po polsku, np. "Październik 2026" (do nagłówków grup w historii). */
    fun monthYear(millis: Long): String =
        monthPattern.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
            .replaceFirstChar { it.uppercase() }

    /** Godzina lokalna, np. "14:05". */
    fun clock(millis: Long): String =
        clockPattern.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

    fun ageText(fromMillis: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = ((now - fromMillis) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "przed chwilą"
            minutes < 60 -> "$minutes min temu"
            minutes < 24 * 60 -> "${minutes / 60} godz. temu"
            else -> "${minutes / (24 * 60)} dn. temu"
        }
    }
}

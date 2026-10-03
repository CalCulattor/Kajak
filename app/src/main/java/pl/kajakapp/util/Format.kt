package pl.kajakapp.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Fmt {
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

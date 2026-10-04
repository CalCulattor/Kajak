package pl.kajakapp.domain

/**
 * Dane starsze niż limity poniżej są pokazywane użytkownikowi, ale nie biorą udziału w ocenie ryzyka.
 *
 * Wodowskazy IMGW raportują w różnych odstępach (część co godzinę, część kilka razy na dobę), dlatego
 * dla pomiaru wody limit jest dłuższy niż dla prognozy pogody, która odświeża się przy każdym pobraniu.
 */
object DataFreshness {
    const val MAX_AGE_HOURS = 6L
    const val MAX_AGE_MS = MAX_AGE_HOURS * 60L * 60L * 1000L

    /** Najstarszy pomiar wodowskazu, jaki jeszcze uznajemy za aktualny. */
    const val WATER_MEASUREMENT_MAX_AGE_HOURS = 12L
    const val WATER_MEASUREMENT_MAX_AGE_MS = WATER_MEASUREMENT_MAX_AGE_HOURS * 60L * 60L * 1000L

    fun isFresh(water: WaterReading, now: Long): Boolean {
        if (now - water.fetchedAt > MAX_AGE_MS) return false
        val measured = water.measuredAtMillis
        return measured == null || now - measured <= WATER_MEASUREMENT_MAX_AGE_MS
    }

    fun isFresh(weather: WeatherSnapshot, now: Long): Boolean =
        now - weather.fetchedAt <= MAX_AGE_MS
}

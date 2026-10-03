package pl.kajakapp.domain

/**
 * Dane starsze niż [MAX_AGE_MS] (zarówno pod względem pobrania, jak i samego pomiaru)
 * są pokazywane użytkownikowi, ale nie biorą udziału w ocenie ryzyka.
 */
object DataFreshness {
    const val MAX_AGE_HOURS = 6L
    const val MAX_AGE_MS = MAX_AGE_HOURS * 60L * 60L * 1000L

    fun isFresh(water: WaterReading, now: Long): Boolean {
        if (now - water.fetchedAt > MAX_AGE_MS) return false
        val measured = water.measuredAtMillis
        return measured == null || now - measured <= MAX_AGE_MS
    }

    fun isFresh(weather: WeatherSnapshot, now: Long): Boolean =
        now - weather.fetchedAt <= MAX_AGE_MS
}

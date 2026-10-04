package pl.kajakapp.domain

import java.text.Normalizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Stacja hydrologiczna IMGW sprowadzona do tego, czego potrzebuje dopasowanie. */
data class StationCandidate(
    val name: String,
    val river: String,
    val lat: Double?,
    val lon: Double?,
    /** false, gdy stacja nie podaje teraz stanu wody (przy wyborze automatycznym schodzi na dalszy plan). */
    val hasLevel: Boolean = true
)

data class StationMatch(val index: Int, val distanceKm: Double?)

/**
 * Wybiera wodowskaz IMGW dla odcinka. Nazwy w danych IMGW bywają zapisane inaczej niż wpisze je człowiek
 * (wielkość liter, polskie znaki, numer w nawiasie przy rzece, np. „Wisła (2)”), więc porównujemy je
 * po normalizacji. Gdy nie podano stacji, szukamy najbliższej na tej samej rzece.
 */
object StationMatcher {
    /** Najdalszy wodowskaz, który jeszcze uznajemy za reprezentatywny dla odcinka. */
    const val MAX_AUTO_DISTANCE_KM = 60.0

    fun normalize(text: String?): String {
        if (text == null) return ""
        val noParens = text.replace(Regex("""\s*\(\d+\)\s*$"""), "")
        val decomposed = Normalizer.normalize(noParens.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('ł', 'l')
        return decomposed.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun sameRiver(stationRiver: String, riverName: String): Boolean {
        val a = normalize(stationRiver)
        val b = normalize(riverName)
        return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.startsWith("$b ") || b.startsWith("$a "))
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun distanceTo(c: StationCandidate, lat: Double, lon: Double): Double? =
        if (c.lat != null && c.lon != null) distanceKm(lat, lon, c.lat, c.lon) else null

    /**
     * @param stationName nazwa wpisana przez użytkownika (null/pusta = wybierz automatycznie)
     * @param hints teksty opisujące odcinek (nazwa, start, meta) – używane tylko, gdy stacje nie mają współrzędnych
     * @return indeks stacji z [stations] albo null, gdy nic nie pasuje
     */
    fun pick(
        stations: List<StationCandidate>,
        stationName: String?,
        riverName: String,
        lat: Double,
        lon: Double,
        hints: List<String> = emptyList()
    ): StationMatch? {
        val wanted = normalize(stationName)
        if (wanted.isNotEmpty()) return pickByName(stations, wanted, riverName, lat, lon)
        return pickAutomatically(stations, riverName, lat, lon, hints)
    }

    private fun pickByName(
        stations: List<StationCandidate>,
        wanted: String,
        riverName: String,
        lat: Double,
        lon: Double
    ): StationMatch? {
        var matches = stations.indices.filter { normalize(stations[it].name) == wanted }
        if (matches.isEmpty()) {
            // Nazwa wpisana skrótowo (np. „Sromowce”) – dopuszczamy początek nazwy stacji.
            matches = stations.indices.filter { normalize(stations[it].name).startsWith("$wanted ") }
        }
        if (matches.isEmpty()) return null
        val onRiver = matches.filter { sameRiver(stations[it].river, riverName) }
        val pool = onRiver.ifEmpty { matches }
        val best = pool.minByOrNull { distanceTo(stations[it], lat, lon) ?: Double.MAX_VALUE } ?: return null
        return StationMatch(best, distanceTo(stations[best], lat, lon))
    }

    private fun pickAutomatically(
        stations: List<StationCandidate>,
        riverName: String,
        lat: Double,
        lon: Double,
        hints: List<String>
    ): StationMatch? {
        val onRiverAll = stations.indices.filter { sameRiver(stations[it].river, riverName) }
        if (onRiverAll.isEmpty()) return null
        // Stacja bez bieżącego odczytu jest mało przydatna, o ile na rzece są inne.
        val onRiver = onRiverAll.filter { stations[it].hasLevel }.ifEmpty { onRiverAll }

        val withDistance = onRiver.mapNotNull { i -> distanceTo(stations[i], lat, lon)?.let { i to it } }
        if (withDistance.isNotEmpty()) {
            val (index, dist) = withDistance.minByOrNull { it.second }!!
            return if (dist <= MAX_AUTO_DISTANCE_KM) StationMatch(index, dist) else null
        }

        // Stacje bez współrzędnych: wybieramy tę, której nazwa pojawia się w opisie odcinka.
        val words = hints.flatMap { normalize(it).split(' ') }.filter { it.length >= 4 }.toSet()
        val byName = onRiver.filter { i -> normalize(stations[i].name).split(' ').any { it.length >= 4 && it in words } }
        if (byName.size == 1) return StationMatch(byName.first(), null)
        // Jedyna stacja na rzece to jedyny sensowny wybór.
        return if (onRiver.size == 1) StationMatch(onRiver.first(), null) else null
    }
}

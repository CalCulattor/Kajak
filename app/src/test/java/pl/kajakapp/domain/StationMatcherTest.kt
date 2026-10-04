package pl.kajakapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationMatcherTest {

    private val stations = listOf(
        StationCandidate("Sromowce Wyżne", "Dunajec", 49.395, 20.355),
        StationCandidate("Krościenko", "Dunajec", 49.43, 20.42),
        StationCandidate("Nowy Targ", "Dunajec", 49.47, 20.03),
        StationCandidate("Tarnów", "Dunajec", 50.01, 20.98),
        StationCandidate("Warszawa-Bulwary", "Wisła (2)", 52.24, 21.03),
        StationCandidate("Krościenko", "Kamionka", 49.9, 22.1)
    )

    @Test
    fun normalize_stripsDiacriticsCaseAndRiverNumber() {
        assertEquals("sromowce wyzne", StationMatcher.normalize("Sromowce Wyżne"))
        assertEquals("lodz", StationMatcher.normalize("ŁÓDŹ"))
        assertEquals("wisla", StationMatcher.normalize("Wisła (2)"))
        assertEquals("warszawa bulwary", StationMatcher.normalize("  Warszawa-Bulwary "))
    }

    @Test
    fun byName_ignoresCaseAndPolishLetters() {
        val m = StationMatcher.pick(stations, "sromowce wyzne", "Dunajec", 49.4, 20.4)
        assertEquals(0, m!!.index)
    }

    @Test
    fun byName_prefersTheSameRiver() {
        val m = StationMatcher.pick(stations, "Krościenko", "Dunajec", 49.44, 20.43)!!
        assertEquals(1, m.index)
        val other = StationMatcher.pick(stations, "Krościenko", "Kamionka", 49.9, 22.1)!!
        assertEquals(5, other.index)
    }

    @Test
    fun byName_unknownStation_isNull() {
        assertNull(StationMatcher.pick(stations, "Nieistniejąca", "Dunajec", 49.4, 20.4))
    }

    @Test
    fun byName_riverWithNumberSuffixMatches() {
        val m = StationMatcher.pick(stations, "Warszawa-Bulwary", "Wisła", 52.2, 21.0)
        assertEquals(4, m!!.index)
    }

    @Test
    fun auto_picksNearestStationOnTheSameRiver() {
        val m = StationMatcher.pick(stations, null, "Dunajec", 49.44, 20.43)!!
        assertEquals(1, m.index)
        assertNotNull(m.distanceKm)
        assertTrue(m.distanceKm!! < 5.0)
    }

    @Test
    fun auto_ignoresStationsOnOtherRivers() {
        // Najbliższa stacja w ogóle to inna rzeka – nie wolno jej wybrać.
        val near = listOf(
            StationCandidate("Blisko", "Potok", 49.44, 20.43),
            StationCandidate("Daleko", "Dunajec", 49.50, 20.50)
        )
        assertEquals(1, StationMatcher.pick(near, null, "Dunajec", 49.44, 20.43)!!.index)
    }

    @Test
    fun auto_tooFar_isNull() {
        assertNull(StationMatcher.pick(stations, null, "Wisła", 49.6, 19.0))
    }

    @Test
    fun auto_prefersStationWithCurrentReading() {
        val list = listOf(
            StationCandidate("Bez odczytu", "Rzeka", 50.0, 20.0, hasLevel = false),
            StationCandidate("Z odczytem", "Rzeka", 50.1, 20.1, hasLevel = true)
        )
        assertEquals(1, StationMatcher.pick(list, null, "Rzeka", 50.0, 20.0)!!.index)
    }

    @Test
    fun auto_withoutCoordinates_usesSectionTextHints() {
        val list = listOf(
            StationCandidate("Sromowce Wyżne", "Dunajec", null, null),
            StationCandidate("Tarnów", "Dunajec", null, null)
        )
        val m = StationMatcher.pick(list, null, "Dunajec", 49.4, 20.4, hints = listOf("Sromowce – Szczawnica", "Sromowce"))!!
        assertEquals(0, m.index)
        assertNull(m.distanceKm)
    }

    @Test
    fun auto_withoutCoordinatesAndNoHints_isNullWhenAmbiguous_butSingleStationIsTaken() {
        val two = listOf(
            StationCandidate("A", "Rzeka", null, null),
            StationCandidate("B", "Rzeka", null, null)
        )
        assertNull(StationMatcher.pick(two, null, "Rzeka", 50.0, 20.0))
        assertEquals(0, StationMatcher.pick(two.take(1), null, "Rzeka", 50.0, 20.0)!!.index)
    }
}

package pl.kajakapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStatsTest {
    private val metersPerDegLat = 111_194.9
    private val t0 = 1_700_000_000_000L

    /** Płynie na północ ze stałą prędkością; zwraca punkty co [stepS] sekund. */
    private fun north(
        fromS: Int, toS: Int, speedMs: Double, startLat: Double = 50.0, stepS: Int = 3, offsetM: Double = 0.0
    ): List<TrackPoint> =
        (fromS..toS step stepS).map { s ->
            TrackPoint(t0 + s * 1000L, startLat + (offsetM + speedMs * s) / metersPerDegLat, 20.0, 5f)
        }

    @Test
    fun geoDistance_matchesKnownValue() {
        // 0,001° szerokości geograficznej ≈ 111,19 m
        assertEquals(111.19, Geo.distanceM(50.0, 20.0, 50.001, 20.0), 0.2)
        assertEquals(0.0, Geo.distanceM(50.0, 20.0, 50.0, 20.0), 1e-9)
    }

    @Test
    fun steadyPaddling_hasExpectedBasics() {
        val s = TrackAnalyzer.analyze(north(0, 600, 1.5))
        assertEquals(900.0, s.distanceM, 900 * 0.02)
        assertEquals(600_000L, s.elapsedMs)
        assertEquals(600_000.0, s.movingMs.toDouble(), 6_000.0)
        assertEquals(5.4, s.avgSpeedKmh, 0.15)
        assertEquals(5.4, s.avgMovingSpeedKmh, 0.15)
        assertEquals(5.4, s.maxSpeedKmh, 0.3)
        assertEquals(0, s.stops)
        assertEquals(60.0 / 5.4, s.paceMinPerKm!!, 0.3)
        assertTrue(s.hasData)
    }

    @Test
    fun kilometreSplits_fullAndPartial() {
        val s = TrackAnalyzer.analyze(north(0, 1750, 2.0)) // 3,5 km
        assertEquals(3500.0, s.distanceM, 70.0)
        assertEquals(4, s.splits.size)
        for (i in 0..2) {
            val sp = s.splits[i]
            assertEquals(i + 1, sp.index)
            assertEquals(500_000.0, sp.durationMs.toDouble(), 8_000.0)
            assertEquals(7.2, sp.speedKmh, 0.3)
            assertFalse(sp.isPartial)
        }
        val last = s.splits[3]
        assertTrue(last.isPartial)
        assertEquals(500.0, last.distanceM, 70.0)
    }

    @Test
    fun stationaryJitter_isStopNotDistance() {
        // 10 min w ruchu (900 m), 5 min postoju z drżeniem GPS (±2 m), 5 min w ruchu (450 m)
        val first = north(0, 600, 1.5)
        val endLat = first.last().lat
        val still = (603..900 step 3).map { s ->
            val wobble = if ((s / 3) % 2 == 0) 2.0 else -2.0
            TrackPoint(t0 + s * 1000L, endLat + wobble / metersPerDegLat, 20.0, 5f)
        }
        val second = (903..1200 step 3).map { s ->
            TrackPoint(t0 + s * 1000L, endLat + 1.5 * (s - 900) / metersPerDegLat, 20.0, 5f)
        }
        val s = TrackAnalyzer.analyze(first + still + second)
        assertEquals(1350.0, s.distanceM, 1350 * 0.03)
        assertEquals(1, s.stops)
        assertEquals(300_000.0, s.stoppedMs.toDouble(), 15_000.0)
        assertEquals(1_200_000L, s.elapsedMs)
        assertEquals(5.4, s.avgMovingSpeedKmh, 0.3)
        assertTrue(s.avgSpeedKmh < s.avgMovingSpeedKmh)
    }

    @Test
    fun gpsSpike_isIgnored() {
        val clean = north(0, 300, 1.5)
        val withSpike = clean.toMutableList()
        val mid = withSpike[50]
        withSpike[50] = mid.copy(lat = mid.lat + 0.05) // ok. 5,5 km skoku w 3 s
        val a = TrackAnalyzer.analyze(clean)
        val b = TrackAnalyzer.analyze(withSpike)
        assertEquals(a.distanceM, b.distanceM, 15.0)
        assertTrue(b.droppedPoints >= 1)
    }

    @Test
    fun inaccuratePointsAndDuplicates_areDropped() {
        val pts = north(0, 60, 1.5).toMutableList()
        pts.add(TrackPoint(t0 + 31_000L, 50.5, 20.0, 200f)) // zbyt niedokładny
        pts.add(pts[3]) // duplikat
        val s = TrackAnalyzer.analyze(pts)
        assertEquals(90.0, s.distanceM, 5.0)
        assertEquals(2, s.droppedPoints)
    }

    @Test
    fun longGap_isNotCountedAsDistance() {
        val a = north(0, 300, 1.5)
        val lastLat = a.last().lat
        // 20 min bez sygnału, potem odczyty 2 km dalej
        val b = (1500..1800 step 3).map { s ->
            TrackPoint(t0 + s * 1000L, lastLat + (2000.0 + 1.5 * (s - 1500)) / metersPerDegLat, 20.0, 5f)
        }
        val s = TrackAnalyzer.analyze(a + b)
        assertEquals(900.0, s.distanceM, 900 * 0.05) // 450 m + 450 m, bez 2 km skoku
        assertTrue(s.stoppedMs >= 1_100_000L)
        assertTrue(s.stops >= 1)
    }

    @Test
    fun tooFewPoints_giveEmptySummary() {
        assertFalse(TrackAnalyzer.analyze(emptyList()).hasData)
        val one = TrackAnalyzer.analyze(listOf(TrackPoint(t0, 50.0, 20.0, 5f)))
        assertFalse(one.hasData)
        assertEquals(0.0, one.distanceM, 0.0)
        assertNull(one.paceMinPerKm)
    }

    @Test
    fun speedProfile_followsPaceChange() {
        val slow = north(0, 300, 1.0)
        val fast = (303..600 step 3).map { s ->
            TrackPoint(t0 + s * 1000L, 50.0 + (300.0 + 3.0 * (s - 300)) / metersPerDegLat, 20.0, 5f)
        }
        val s = TrackAnalyzer.analyze(slow + fast)
        val profile = s.speedProfile
        assertEquals(20, profile.size)
        assertEquals(3.6, profile.first().speedKmh, 0.5)
        assertEquals(10.8, profile.last().speedKmh, 0.8)
        assertTrue(s.maxSpeedKmh > 10.0)
    }

    @Test
    fun liveSpeed_dropsToZeroWhenStanding() {
        val acc = TrackAccumulator()
        for (p in north(0, 90, 1.5)) acc.add(p)
        val moving = acc.currentSpeedMs(t0 + 90_000L)
        assertEquals(1.5, moving, 0.2)
        assertEquals(0.0, acc.currentSpeedMs(t0 + 140_000L), 0.01)
        assertEquals(135.0, acc.distanceM, 5.0)
    }

    @Test
    fun pathIsSampledDown() {
        val s = TrackAnalyzer.analyze(north(0, 6000, 1.5)) // 2001 punktów
        assertTrue(s.path.size <= TrackAccumulator.MAX_PATH_POINTS + 1)
        assertTrue(s.path.size > 100)
    }
}

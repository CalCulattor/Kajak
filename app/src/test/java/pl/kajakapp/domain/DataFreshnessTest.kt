package pl.kajakapp.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataFreshnessTest {
    private val hour = 60L * 60L * 1000L
    private val now = 100 * hour

    @Test
    fun recentWaterReading_isFresh() {
        val w = WaterReading("S", 100, measuredAtMillis = now - hour, fetchedAt = now - hour)
        assertTrue(DataFreshness.isFresh(w, now))
    }

    @Test
    fun oldFetch_isStale() {
        val w = WaterReading("S", 100, measuredAtMillis = null, fetchedAt = now - 7 * hour)
        assertFalse(DataFreshness.isFresh(w, now))
    }

    @Test
    fun freshFetchButOldMeasurement_isStale() {
        val w = WaterReading("S", 100, measuredAtMillis = now - 13 * hour, fetchedAt = now)
        assertFalse(DataFreshness.isFresh(w, now))
    }

    @Test
    fun stationReportingFewTimesADay_isStillFreshWithin12Hours() {
        val w = WaterReading("S", 100, measuredAtMillis = now - 10 * hour, fetchedAt = now)
        assertTrue(DataFreshness.isFresh(w, now))
    }

    @Test
    fun weatherFreshness() {
        assertTrue(DataFreshness.isFresh(WeatherSnapshot(10.0, 5.0, 0.0, false, now - hour), now))
        assertFalse(DataFreshness.isFresh(WeatherSnapshot(10.0, 5.0, 0.0, false, now - 7 * hour), now))
    }
}

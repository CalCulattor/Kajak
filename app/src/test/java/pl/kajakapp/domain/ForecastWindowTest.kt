package pl.kajakapp.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastWindowTest {

    // Doba od północy: temperatura rośnie, deszcz tylko 14:00, burza o 15:00, porywy rosną z godziną.
    private val times = (0 until 24).map { "2026-10-05T%02d:00".format(it) }
    private val temp = (0 until 24).map { 10.0 + it }
    private val rain = (0 until 24).map { if (it == 14) 2.5 else 0.0 }
    private val gust = (0 until 24).map { 3.0 + it * 0.5 }
    private val codes = (0 until 24).map { if (it == 15) 95 else 1 }

    private fun summarize(start: LocalDateTime, hours: Int = 4) =
        ForecastWindow.summarize(times, temp, rain, gust, codes, start, hours, fetchedAt = 123L)

    @Test
    fun window_aggregatesRequestedHours() {
        val s = summarize(LocalDateTime.of(2026, 10, 5, 12, 30))!!
        // 12:00, 13:00, 14:00, 15:00
        assertEquals(2.5, s.precipitationMm!!, 1e-9)
        assertEquals(3.0 + 15 * 0.5, s.windGustMs!!, 1e-9)
        assertEquals((22.0 + 23.0 + 24.0 + 25.0) / 4, s.airTempC!!, 1e-9)
        assertTrue(s.thunderstorm)
        assertEquals(4, s.windowHours)
        assertEquals(123L, s.fetchedAt)
    }

    @Test
    fun window_withoutStormOrRain() {
        val s = summarize(LocalDateTime.of(2026, 10, 5, 6, 0))!!
        assertFalse(s.thunderstorm)
        assertEquals(0.0, s.precipitationMm!!, 1e-9)
    }

    @Test
    fun window_noMatchingHours_isNull() {
        assertNull(summarize(LocalDateTime.of(2026, 10, 9, 10, 0)))
    }

    @Test
    fun window_truncatedAtEndOfData_stillSummarizes() {
        val s = summarize(LocalDateTime.of(2026, 10, 5, 22, 0))
        assertNotNull(s)
        assertEquals(3.0 + 23 * 0.5, s!!.windGustMs!!, 1e-9)
    }

    @Test
    fun window_withMissingValues_usesWhatIsThere() {
        val s = ForecastWindow.summarize(
            times = listOf("2026-10-05T10:00", "2026-10-05T11:00"),
            temperature = listOf(null, 12.0),
            precipitation = listOf(null, null),
            gusts = listOf(null, 7.0),
            codes = listOf(null, 3),
            start = LocalDateTime.of(2026, 10, 5, 10, 0),
            hours = 2,
            fetchedAt = 1L
        )!!
        assertEquals(12.0, s.airTempC!!, 1e-9)
        assertNull(s.precipitationMm)
        assertEquals(7.0, s.windGustMs!!, 1e-9)
    }

    @Test
    fun window_withNoValuesAtAll_isNull() {
        val s = ForecastWindow.summarize(
            listOf("2026-10-05T10:00"), listOf(null), listOf(null), listOf(null), listOf(null),
            LocalDateTime.of(2026, 10, 5, 10, 0), 4, 1L
        )
        assertNull(s)
    }

    @Test
    fun range_checksPastAndHorizon() {
        val now = LocalDateTime.of(2026, 10, 4, 15, 0)
        assertEquals(ForecastRange.OK, ForecastWindow.range(LocalDateTime.of(2026, 10, 4, 6, 0), now))
        assertEquals(ForecastRange.OK, ForecastWindow.range(LocalDateTime.of(2026, 10, 19, 23, 0), now))
        assertEquals(ForecastRange.PAST, ForecastWindow.range(LocalDateTime.of(2026, 10, 3, 23, 0), now))
        assertEquals(ForecastRange.TOO_FAR, ForecastWindow.range(LocalDateTime.of(2026, 10, 20, 8, 0), now))
    }
}

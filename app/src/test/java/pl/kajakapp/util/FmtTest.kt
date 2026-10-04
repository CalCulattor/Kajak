package pl.kajakapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test
    fun duration_formatsMinutesAndHours() {
        assertEquals("0:00", Fmt.duration(0))
        assertEquals("1:05", Fmt.duration(65_000))
        assertEquals("1:01:01", Fmt.duration(3_661_000))
        assertEquals("0:00", Fmt.duration(-5_000))
    }

    @Test
    fun distance_usesMetresBelowOneKilometre() {
        assertEquals("850 m", Fmt.distance(850.4))
    }

    @Test
    fun pace_handlesMissingValue() {
        assertEquals("–", Fmt.pace(null))
    }
}

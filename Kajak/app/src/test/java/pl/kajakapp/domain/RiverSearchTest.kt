package pl.kajakapp.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiverSearchTest {
    @Test
    fun emptyQueryMatchesEverything() {
        assertTrue(RiverSearch.matches("  ", "Wda"))
    }

    @Test
    fun ignoresCaseAndPolishLetters() {
        assertTrue(RiverSearch.matches("wisla", "Wisła"))
        assertTrue(RiverSearch.matches("SWIECIE", "Osie – Świecie"))
        assertTrue(RiverSearch.matches("lupawa", "Łupawa"))
        assertTrue(RiverSearch.matches("Wisła", "wisla"))
    }

    @Test
    fun allWordsMustMatchAcrossFields() {
        assertTrue(RiverSearch.matches("pilica tomaszow", "Pilica", "Tomaszów Mazowiecki – Inowłódz"))
        assertFalse(RiverSearch.matches("pilica sanok", "Pilica", "Tomaszów Mazowiecki – Inowłódz"))
    }
}

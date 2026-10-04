package pl.kajakapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GearRulesTest {

    @Test
    fun requirement_isNormalized() {
        assertEquals(GearRules.REQUIRED, GearRules.normalizeRequirement("required"))
        assertEquals(GearRules.RECOMMENDED, GearRules.normalizeRequirement("recommended"))
        assertEquals(GearRules.RECOMMENDED, GearRules.normalizeRequirement(null))
        assertEquals(GearRules.RECOMMENDED, GearRules.normalizeRequirement("cokolwiek"))
        assertEquals("Wymagane", GearRules.requirementLabel(GearRules.REQUIRED))
        assertEquals("Zalecane", GearRules.requirementLabel(GearRules.RECOMMENDED))
    }

    @Test
    fun noOneConfirmedByDefault() {
        assertFalse(GearRules.isConfirmed("", "Ola"))
        assertEquals(emptyList<String>(), GearRules.decode(""))
    }

    @Test
    fun confirmation_isPerPerson() {
        val afterOla = GearRules.withConfirmation("", "Ola", true)
        assertTrue(GearRules.isConfirmed(afterOla, "Ola"))
        assertTrue(GearRules.isConfirmed(afterOla, "ola"))
        assertFalse(GearRules.isConfirmed(afterOla, "Ewa"))

        val afterEwa = GearRules.withConfirmation(afterOla, "Ewa", true)
        assertEquals(listOf("Ola", "Ewa"), GearRules.decode(afterEwa))

        val olaWithdraws = GearRules.withConfirmation(afterEwa, "ola", false)
        assertEquals(listOf("Ewa"), GearRules.decode(olaWithdraws))
    }

    @Test
    fun confirmingTwice_doesNotDuplicate() {
        var raw = GearRules.withConfirmation("", "Ola", true)
        raw = GearRules.withConfirmation(raw, "OLA", true)
        assertEquals(1, GearRules.decode(raw).size)
    }

    @Test
    fun encode_dropsBlanksAndDuplicates() {
        assertEquals("Ola\nEwa", GearRules.encode(listOf(" Ola ", "", "ola", "Ewa")))
    }
}

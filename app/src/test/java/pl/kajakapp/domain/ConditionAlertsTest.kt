package pl.kajakapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionAlertsTest {
    @Test
    fun noPreviousLevel_noAlert() {
        assertNull(ConditionAlerts.alert(null, RiskLevel.EXTREME, "Dunajec"))
    }

    @Test
    fun sameLevel_noAlert() {
        assertNull(ConditionAlerts.alert(RiskLevel.ELEVATED, RiskLevel.ELEVATED, "Dunajec"))
    }

    @Test
    fun unknownIsNeverAChange() {
        assertNull(ConditionAlerts.alert(RiskLevel.FAVORABLE, RiskLevel.UNKNOWN, "Dunajec"))
        assertNull(ConditionAlerts.alert(RiskLevel.UNKNOWN, RiskLevel.FAVORABLE, "Dunajec"))
    }

    @Test
    fun worsening_isReported() {
        val a = ConditionAlerts.alert(RiskLevel.FAVORABLE, RiskLevel.ELEVATED, "Dunajec")
        assertNotNull(a)
        assertFalse(a!!.improved)
        assertTrue(a.title.contains("pogorszyły"))
        assertTrue(a.text.contains(RiskLevel.FAVORABLE.label) && a.text.contains(RiskLevel.ELEVATED.label))
    }

    @Test
    fun improvement_isReported() {
        val a = ConditionAlerts.alert(RiskLevel.EXTREME, RiskLevel.FAVORABLE, "Dunajec", "jutro")
        assertTrue(a!!.improved)
        assertEquals(true, a.text.endsWith("(jutro)"))
    }
}

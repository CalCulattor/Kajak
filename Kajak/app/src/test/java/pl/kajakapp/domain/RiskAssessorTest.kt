package pl.kajakapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAssessorTest {

    private fun water(
        level: Int? = 100,
        warning: Int? = 200,
        alarm: Int? = 300,
        temp: Double? = 15.0,
        ice: Boolean = false
    ) = WaterReading(
        stationName = "Test",
        levelCm = level,
        waterTempC = temp,
        iceActive = ice,
        warningCm = warning,
        alarmCm = alarm
    )

    private fun weather(
        gust: Double? = 5.0,
        rain: Double? = 0.0,
        air: Double? = 18.0,
        storm: Boolean = false
    ) = WeatherSnapshot(airTempC = air, windGustMs = gust, precipitationMm = rain, thunderstorm = storm)

    @Test
    fun noData_isUnknown() {
        assertEquals(RiskLevel.UNKNOWN, RiskAssessor.assess(null, null, emptyList()).level)
    }

    @Test
    fun goodConditions_areFavorable() {
        val r = RiskAssessor.assess(water(), weather(), emptyList())
        assertEquals(RiskLevel.FAVORABLE, r.level)
    }

    @Test
    fun missingWeather_neverFavorable() {
        val r = RiskAssessor.assess(water(), null, emptyList())
        assertEquals(RiskLevel.UNKNOWN, r.level)
    }

    @Test
    fun missingThresholds_waterIsInformationalOnly() {
        // Stacja bez progów: odczyt jest widoczny, ale ocenę wyznacza pogoda i przeszkody.
        val r = RiskAssessor.assess(water(warning = null, alarm = null), weather(), emptyList())
        assertEquals(RiskLevel.FAVORABLE, r.level)
        assertTrue(r.factors.any { it.informational && it.message.contains("oceń poziom samodzielnie") })
    }

    @Test
    fun missingThresholds_stillWarnsAboutBadWeather() {
        val r = RiskAssessor.assess(water(warning = null, alarm = null), weather(storm = true), emptyList())
        assertEquals(RiskLevel.EXTREME, r.level)
    }

    @Test
    fun noWaterReading_isStillUnknown() {
        assertEquals(RiskLevel.UNKNOWN, RiskAssessor.assess(null, weather(), emptyList()).level)
    }

    @Test
    fun forecast_ignoresMissingWater_butUsesWeather() {
        val ok = RiskAssessor.assess(null, weather(), emptyList(), forecast = true)
        assertEquals(RiskLevel.FAVORABLE, ok.level)
        val bad = RiskAssessor.assess(null, weather(gust = 21.0), emptyList(), forecast = true)
        assertEquals(RiskLevel.EXTREME, bad.level)
    }

    @Test
    fun forecast_stillWarnsWhenWaterIsAlreadyHigh() {
        val r = RiskAssessor.assess(water(level = 250), weather(), emptyList(), forecast = true)
        assertEquals(RiskLevel.ELEVATED, r.level)
    }

    @Test
    fun forecast_missingWeather_isUnknown() {
        assertEquals(RiskLevel.UNKNOWN, RiskAssessor.assess(water(), null, emptyList(), forecast = true).level)
    }

    @Test
    fun windowRain_usesLowerThresholds() {
        val w = WeatherSnapshot(18.0, 5.0, 10.0, false, windowHours = 4)
        assertEquals(RiskLevel.ELEVATED, RiskAssessor.assess(water(), w, emptyList(), forecast = true).level)
        val heavy = w.copy(precipitationMm = 25.0)
        assertEquals(RiskLevel.EXTREME, RiskAssessor.assess(water(), heavy, emptyList(), forecast = true).level)
        // Te same 10 mm w skali doby to jeszcze nie ostrzeżenie.
        assertEquals(RiskLevel.FAVORABLE, RiskAssessor.assess(water(), weather(rain = 10.0), emptyList()).level)
    }

    @Test
    fun waterAboveWarning_isElevated() {
        val r = RiskAssessor.assess(water(level = 250), weather(), emptyList())
        assertEquals(RiskLevel.ELEVATED, r.level)
    }

    @Test
    fun waterAboveAlarm_isExtreme() {
        val r = RiskAssessor.assess(water(level = 300), weather(), emptyList())
        assertEquals(RiskLevel.EXTREME, r.level)
    }

    @Test
    fun thunderstorm_isExtreme() {
        val r = RiskAssessor.assess(water(), weather(storm = true), emptyList())
        assertEquals(RiskLevel.EXTREME, r.level)
    }

    @Test
    fun strongGusts_areElevated_veryStrongAreExtreme() {
        assertEquals(
            RiskLevel.ELEVATED,
            RiskAssessor.assess(water(), weather(gust = 15.0), emptyList()).level
        )
        assertEquals(
            RiskLevel.EXTREME,
            RiskAssessor.assess(water(), weather(gust = 21.0), emptyList()).level
        )
    }

    @Test
    fun heavyRain_isElevated() {
        val r = RiskAssessor.assess(water(), weather(rain = 25.0), emptyList())
        assertEquals(RiskLevel.ELEVATED, r.level)
    }

    @Test
    fun coldWater_isElevated() {
        val r = RiskAssessor.assess(water(temp = 6.0), weather(), emptyList())
        assertEquals(RiskLevel.ELEVATED, r.level)
    }

    @Test
    fun iceOnRiver_isElevated() {
        val r = RiskAssessor.assess(water(ice = true), weather(), emptyList())
        assertEquals(RiskLevel.ELEVATED, r.level)
    }

    @Test
    fun activeStrainer_raisesRisk_evenWhenEverythingElseIsFine() {
        val r = RiskAssessor.assess(water(), weather(), listOf(ObstacleType.STRAINER))
        assertEquals(RiskLevel.ELEVATED, r.level)
    }

    @Test
    fun portageOnly_doesNotRaiseRisk() {
        val r = RiskAssessor.assess(water(), weather(), listOf(ObstacleType.PORTAGE))
        assertEquals(RiskLevel.FAVORABLE, r.level)
    }

    @Test
    fun worstFactorWins() {
        val r = RiskAssessor.assess(
            water(level = 250), weather(storm = true), listOf(ObstacleType.WEIR)
        )
        assertEquals(RiskLevel.EXTREME, r.level)
        assertEquals(RiskLevel.EXTREME, r.factors.first().level)
    }

    @Test
    fun obstacleRules_removalNeedsTwoVotesAndMajority() {
        assertTrue(ObstacleRules.isActive(confirmations = 1, removalVotes = 0))
        assertTrue(ObstacleRules.isActive(confirmations = 1, removalVotes = 1))
        assertFalse(ObstacleRules.isActive(confirmations = 1, removalVotes = 2))
        assertTrue(ObstacleRules.isActive(confirmations = 3, removalVotes = 2))
    }

    @Test
    fun obstacleRules_staleAfter30Days() {
        val day = 24L * 60 * 60 * 1000
        assertFalse(ObstacleRules.isStale(lastVerifiedAt = 0, now = 30 * day))
        assertTrue(ObstacleRules.isStale(lastVerifiedAt = 0, now = 31 * day))
    }
}

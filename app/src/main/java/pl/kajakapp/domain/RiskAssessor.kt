package pl.kajakapp.domain

import java.util.Locale

/**
 * Czysta logika oceny ryzyka (bez zależności od Androida), więc łatwa do testowania.
 * Celowo nie zwraca „bezpiecznie / niebezpiecznie”, tylko poziom ryzyka z uzasadnieniem.
 */
object RiskAssessor {

    const val GUST_ELEVATED_MS = 14.0
    const val GUST_EXTREME_MS = 20.0
    const val RAIN_ELEVATED_MM = 20.0
    const val RAIN_EXTREME_MM = 50.0
    const val COLD_WATER_C = 10.0

    fun assess(
        water: WaterReading?,
        weather: WeatherSnapshot?,
        activeObstacles: List<ObstacleType>
    ): RiskAssessment {
        val factors = buildList {
            addAll(waterFactors(water))
            addAll(weatherFactors(weather))
            addAll(obstacleFactors(activeObstacles))
        }

        val worstKnown = factors
            .filter { it.level != RiskLevel.UNKNOWN }
            .maxOfOrNull { it.level }
        val hasGap = factors.any { it.level == RiskLevel.UNKNOWN }

        // Brak części danych nigdy nie może dać zielonego światła.
        val overall = when {
            worstKnown == null -> RiskLevel.UNKNOWN
            worstKnown == RiskLevel.FAVORABLE && hasGap -> RiskLevel.UNKNOWN
            else -> worstKnown
        }
        return RiskAssessment(overall, factors.sortedByDescending { displayRank(it.level) })
    }

    private fun displayRank(level: RiskLevel): Int = when (level) {
        RiskLevel.EXTREME -> 3
        RiskLevel.ELEVATED -> 2
        RiskLevel.UNKNOWN -> 1
        RiskLevel.FAVORABLE -> 0
    }

    private fun waterFactors(water: WaterReading?): List<RiskFactor> {
        if (water == null) {
            return listOf(RiskFactor(RiskLevel.UNKNOWN, "Brak danych z wodowskazu."))
        }
        val out = mutableListOf<RiskFactor>()
        val level = water.levelCm
        val alarm = water.alarmCm
        val warning = water.warningCm

        if (level == null) {
            out += RiskFactor(RiskLevel.UNKNOWN, "Wodowskaz nie podał stanu wody.")
        } else if (alarm != null && level >= alarm) {
            out += RiskFactor(
                RiskLevel.EXTREME,
                "Stan wody $level cm przekracza stan alarmowy ($alarm cm)."
            )
        } else if (warning != null && level >= warning) {
            out += RiskFactor(
                RiskLevel.ELEVATED,
                "Stan wody $level cm przekracza stan ostrzegawczy ($warning cm)."
            )
        } else if (alarm == null && warning == null) {
            out += RiskFactor(
                RiskLevel.UNKNOWN,
                "Stan wody $level cm, ale stacja nie ma progów ostrzegawczych – oceń poziom samodzielnie."
            )
        } else {
            out += RiskFactor(RiskLevel.FAVORABLE, "Stan wody $level cm poniżej progów ostrzegawczych.")
        }

        if (water.iceActive) {
            out += RiskFactor(RiskLevel.ELEVATED, "Zjawiska lodowe na rzece.")
        }
        val temp = water.waterTempC
        if (temp != null && temp < COLD_WATER_C) {
            out += RiskFactor(
                RiskLevel.ELEVATED,
                "Zimna woda (${fmt1(temp)} °C) – ryzyko wychłodzenia po wywrotce."
            )
        }
        return out
    }

    private fun weatherFactors(weather: WeatherSnapshot?): List<RiskFactor> {
        if (weather == null || !weather.hasData) {
            return listOf(RiskFactor(RiskLevel.UNKNOWN, "Brak prognozy pogody."))
        }
        val out = mutableListOf<RiskFactor>()

        if (weather.thunderstorm) {
            out += RiskFactor(
                RiskLevel.EXTREME,
                "Prognozowane burze – wyładowania, porywisty wiatr, nagły wzrost wody."
            )
        }

        val gust = weather.windGustMs
        if (gust != null) {
            out += when {
                gust >= GUST_EXTREME_MS -> RiskFactor(
                    RiskLevel.EXTREME, "Bardzo silne porywy wiatru: ${fmt1(gust)} m/s."
                )
                gust >= GUST_ELEVATED_MS -> RiskFactor(
                    RiskLevel.ELEVATED, "Silne porywy wiatru: ${fmt1(gust)} m/s."
                )
                else -> RiskFactor(RiskLevel.FAVORABLE, "Porywy wiatru do ${fmt1(gust)} m/s.")
            }
        }

        val rain = weather.precipitationMm
        if (rain != null) {
            out += when {
                rain >= RAIN_EXTREME_MM -> RiskFactor(
                    RiskLevel.EXTREME,
                    "Bardzo intensywne opady (${fmt1(rain)} mm) – możliwy gwałtowny przybór wody."
                )
                rain >= RAIN_ELEVATED_MM -> RiskFactor(
                    RiskLevel.ELEVATED,
                    "Intensywne opady (${fmt1(rain)} mm) – możliwy szybki przybór wody."
                )
                else -> RiskFactor(RiskLevel.FAVORABLE, "Opady dziś: ${fmt1(rain)} mm.")
            }
        }

        val air = weather.airTempC
        if (air != null && air <= 0.0) {
            out += RiskFactor(RiskLevel.ELEVATED, "Temperatura powietrza ${fmt1(air)} °C – mróz.")
        }
        return out
    }

    private fun obstacleFactors(active: List<ObstacleType>): List<RiskFactor> {
        val dangerous = active.filter { it.dangerous }
        val other = active.size - dangerous.size
        val out = mutableListOf<RiskFactor>()

        if (dangerous.isNotEmpty()) {
            val kinds = dangerous.distinct().joinToString(", ") { it.label.lowercase(Locale.ROOT) }
            out += RiskFactor(
                RiskLevel.ELEVATED,
                "Zgłoszone niebezpieczne przeszkody: ${dangerous.size} ($kinds)."
            )
        }
        if (other > 0) {
            out += RiskFactor(RiskLevel.FAVORABLE, "Inne zgłoszone przeszkody (np. przenoski): $other.")
        }
        if (active.isEmpty()) {
            out += RiskFactor(
                RiskLevel.FAVORABLE,
                "Brak zgłoszonych przeszkód (baza zgłoszeń nie jest wyczerpująca)."
            )
        }
        return out
    }

    private fun fmt1(value: Double): String =
        String.format(Locale.forLanguageTag("pl-PL"), "%.1f", value)
}

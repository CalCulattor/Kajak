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

    // Progi dla prognozy na okno godzinowe (kilka godzin na wodzie), a nie na całą dobę.
    const val RAIN_WINDOW_ELEVATED_MM = 8.0
    const val RAIN_WINDOW_EXTREME_MM = 20.0

    /**
     * @param forecast true, gdy [weather] to prognoza na wybrany termin. Stan wody jest wtedy tylko
     *   odczytem z chwili obecnej (nie da się go przewidzieć), więc wskazuje najwyżej na zagrożenie,
     *   ale nigdy nie psuje oceny samym brakiem danych.
     */
    fun assess(
        water: WaterReading?,
        weather: WeatherSnapshot?,
        activeObstacles: List<ObstacleType>,
        forecast: Boolean = false
    ): RiskAssessment {
        val factors = buildList {
            addAll(if (forecast) forecastWaterFactors(water) else waterFactors(water))
            addAll(weatherFactors(weather))
            addAll(obstacleFactors(activeObstacles))
        }

        // Czynniki informacyjne są widoczne dla użytkownika, ale nie wpływają na ocenę ogólną.
        val rated = factors.filterNot { it.informational }
        val worstKnown = rated
            .filter { it.level != RiskLevel.UNKNOWN }
            .maxOfOrNull { it.level }
        val hasGap = rated.any { it.level == RiskLevel.UNKNOWN }

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

    /** Przy prognozie znamy tylko aktualny stan wody – groźny nadal ostrzega, resztę podajemy informacyjnie. */
    private fun forecastWaterFactors(water: WaterReading?): List<RiskFactor> {
        if (water == null) {
            return listOf(
                RiskFactor(RiskLevel.UNKNOWN, "Brak aktualnego odczytu z wodowskazu – prognoza dotyczy tylko pogody.", true)
            )
        }
        val current = waterFactors(water).map { f ->
            val message = "Aktualnie: " + f.message.replaceFirstChar { it.lowercaseChar() }
            when (f.level) {
                RiskLevel.ELEVATED, RiskLevel.EXTREME -> f.copy(message = message)
                else -> f.copy(message = message, informational = true)
            }
        }
        return current + RiskFactor(
            RiskLevel.UNKNOWN,
            "Stanu wody nie da się prognozować – ocena na wybrany termin uwzględnia pogodę i zgłoszone przeszkody.",
            true
        )
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
            // Bez progów nie wiemy, czy 100 cm to dużo, czy mało – pokazujemy odczyt, ale go nie oceniamy.
            out += RiskFactor(
                RiskLevel.UNKNOWN,
                "Stan wody $level cm, ale stacja nie ma progów ostrzegawczych – oceń poziom samodzielnie.",
                informational = true
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
            val window = weather.windowHours
            val extreme = if (window != null) RAIN_WINDOW_EXTREME_MM else RAIN_EXTREME_MM
            val elevated = if (window != null) RAIN_WINDOW_ELEVATED_MM else RAIN_ELEVATED_MM
            val period = if (window != null) "w ciągu $window godz." else "dziś"
            out += when {
                rain >= extreme -> RiskFactor(
                    RiskLevel.EXTREME,
                    "Bardzo intensywne opady (${fmt1(rain)} mm $period) – możliwy gwałtowny przybór wody."
                )
                rain >= elevated -> RiskFactor(
                    RiskLevel.ELEVATED,
                    "Intensywne opady (${fmt1(rain)} mm $period) – możliwy szybki przybór wody."
                )
                else -> RiskFactor(RiskLevel.FAVORABLE, "Opady $period: ${fmt1(rain)} mm.")
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

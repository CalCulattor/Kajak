package pl.kajakapp.domain

enum class RiverType(val label: String) {
    LOWLAND("Nizinna"),
    MOUNTAIN("Górska")
}

enum class Difficulty(val label: String) {
    FLAT("Nizinna (bez trudności)"),
    WW1("WW I"),
    WW2("WW II"),
    WW3("WW III"),
    WW4("WW IV"),
    WW5("WW V")
}

enum class ObstacleType(val label: String, val dangerous: Boolean) {
    STRAINER("Zwałka / drzewo w nurcie", true),
    WEIR("Jaz / próg", true),
    LOW_BRIDGE("Niski most / rura", true),
    ROCK_SIEVE("Rumosz skalny (sito)", true),
    PORTAGE("Przenoska", false),
    OTHER("Inna przeszkoda", false)
}

/** Ostatni odczyt z wodowskazu (IMGW). Progi pochodzą z danych stacji. */
data class WaterReading(
    val stationName: String,
    val levelCm: Int?,
    val levelMeasuredAt: String? = null,
    /** Czas pomiaru stanu wody w ms (UTC), jeśli udało się go odczytać z danych IMGW. */
    val measuredAtMillis: Long? = null,
    val flowM3s: Double? = null,
    val waterTempC: Double? = null,
    val iceActive: Boolean = false,
    val warningCm: Int? = null,
    val alarmCm: Int? = null,
    val fetchedAt: Long = 0L
)

/** Skrót pogody dla odcinka na bieżący dzień. */
data class WeatherSnapshot(
    val airTempC: Double?,
    val windGustMs: Double?,
    val precipitationMm: Double?,
    val thunderstorm: Boolean,
    val fetchedAt: Long = 0L,
    /**
     * null = prognoza na cały dzień (bieżące warunki); wartość = prognoza na okno godzinowe
     * (opady to suma z tego okna, wiatr to najsilniejszy poryw w oknie).
     */
    val windowHours: Int? = null
) {
    val hasData: Boolean
        get() = airTempC != null || windGustMs != null || precipitationMm != null || thunderstorm
}

enum class RiskLevel(val label: String) {
    UNKNOWN("Ocena niepełna – brak danych"),
    FAVORABLE("Warunki sprzyjające"),
    ELEVATED("Podwyższone ryzyko"),
    EXTREME("Skrajne warunki")
}

/**
 * Jeden powód oceny. Czynnik [informational] jest pokazywany użytkownikowi, ale nie zmienia oceny
 * ogólnej (np. stan wody ze stacji bez progów ostrzegawczych – tego poziomu nie da się ocenić automatycznie).
 */
data class RiskFactor(val level: RiskLevel, val message: String, val informational: Boolean = false)

data class RiskAssessment(val level: RiskLevel, val factors: List<RiskFactor>)

const val RISK_DISCLAIMER =
    "To tylko pomoc w ocenie, nie gwarancja bezpieczeństwa. Decyzję o wejściu na wodę " +
        "podejmujesz sam, na podstawie własnych umiejętności i oceny sytuacji na miejscu."

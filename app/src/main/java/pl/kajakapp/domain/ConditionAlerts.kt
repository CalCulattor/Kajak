package pl.kajakapp.domain

/** Treść powiadomienia o zmianie oceny warunków na rzece. */
data class ConditionAlert(val title: String, val text: String, val improved: Boolean)

/**
 * Decyduje, czy zmiana oceny warunków zasługuje na powiadomienie. Brak oceny (UNKNOWN) – np. chwilowy brak
 * danych z IMGW – nigdy nie wywołuje powiadomienia ani nie jest „zmianą”, żeby nie sypać fałszywymi alarmami.
 */
object ConditionAlerts {
    fun alert(previous: RiskLevel?, current: RiskLevel, place: String, when_: String? = null): ConditionAlert? {
        if (previous == null || previous == RiskLevel.UNKNOWN || current == RiskLevel.UNKNOWN) return null
        if (previous == current) return null
        val improved = current < previous
        val title = if (improved) "Warunki się poprawiły: $place" else "Warunki się pogorszyły: $place"
        val suffix = if (when_ != null) " ($when_)" else ""
        return ConditionAlert(title, "${previous.label} → ${current.label}$suffix", improved)
    }
}

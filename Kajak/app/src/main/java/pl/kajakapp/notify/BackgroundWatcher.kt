package pl.kajakapp.notify

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import pl.kajakapp.data.ConditionsRepository
import pl.kajakapp.data.RiverRepository
import pl.kajakapp.data.ServerSettings
import pl.kajakapp.data.SyncRepository
import pl.kajakapp.data.db.TripDao
import pl.kajakapp.domain.ConditionAlerts
import pl.kajakapp.domain.DataFreshness
import pl.kajakapp.domain.ObstacleRules
import pl.kajakapp.domain.RiskAssessor

/**
 * Sprawdza w tle (gdy aplikacja nie jest na ekranie) dwie rzeczy: czy ktoś z naszych spływów prosi o pomoc
 * oraz czy zmieniła się ocena warunków na rzece spływu zaplanowanego na najbliższe dni.
 */
class BackgroundWatcher(
    private val tripDao: TripDao,
    private val settings: ServerSettings,
    private val sync: SyncRepository,
    private val rivers: RiverRepository,
    private val conditions: ConditionsRepository,
    private val prefs: NotificationPrefs,
    private val notifier: Notifier,
    private val isForeground: () -> Boolean
) {
    /** Pełny przebieg (alarm okresowy): synchronizacja spływów, SOS i warunki. */
    suspend fun run() {
        if (settings.session.value == null) return
        sync.syncSharedTrips()
        checkSos()
        checkConditions(refresh = true)
    }

    /**
     * Powiadamia o nowych wezwaniach pomocy: z mojego spływu oraz od osób na tej samej rzece (serwer sam
     * wybiera, kto co widzi). Alarm jest ważny dla bezpieczeństwa, więc pokazujemy go także na pierwszym planie.
     * Odwołane wezwania znikają z paska powiadomień.
     */
    suspend fun checkSos() {
        val me = settings.session.value?.username ?: return
        val alerts = sync.fetchSos() ?: return // brak sieci – spróbujemy przy następnym razie
        val active = alerts.filter { !it.person.equals(me, ignoreCase = true) }
        val activeIds = active.map { it.serverCheckInId }.toSet()
        val known = prefs.notifiedSos
        (known - activeIds).forEach { notifier.cancelSos(it) }
        if (prefs.sosEnabled) {
            for (a in active.filter { it.serverCheckInId !in known }) {
                notifier.sos(a.serverCheckInId, a.localTripId, a.person, a.tripTitle)
            }
        }
        prefs.notifiedSos = activeIds
    }

    /** Porównuje ocenę warunków z ostatnio znaną dla odcinków spływów z najbliższych dni. */
    suspend fun checkConditions(refresh: Boolean) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val upcoming = tripDao.allTrips().filter { trip ->
            val sectionId = trip.sectionId
            val day = Instant.ofEpochMilli(trip.startDateUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            sectionId != null && !day.isBefore(today) && !day.isAfter(today.plusDays(LOOKAHEAD_DAYS))
        }.groupBy { it.sectionId!! }
        for ((sectionId, tripsOnSection) in upcoming) {
            val section = rivers.observeSection(sectionId).first() ?: continue
            if (refresh) conditions.refresh(section.section, section.riverName)
            val now = System.currentTimeMillis()
            val water = conditions.observeWater(sectionId).first()?.takeIf { DataFreshness.isFresh(it, now) }
            val weather = conditions.observeWeather(sectionId).first()?.takeIf { DataFreshness.isFresh(it, now) }
            val obstacles = rivers.observeObstacles(sectionId).first()
                .filter { ObstacleRules.isActive(it.confirmations, it.removalVotes) }
                .map { it.type }
            val level = RiskAssessor.assess(water, weather, obstacles).level
            val previous = prefs.lastLevel(sectionId)?.let { runCatching { enumValueOf<pl.kajakapp.domain.RiskLevel>(it) }.getOrNull() }
            val place = "${section.riverName} – ${section.section.name}"
            val alert = ConditionAlerts.alert(previous, level, place)
            if (alert != null && prefs.conditionsEnabled && !isForeground()) {
                notifier.conditions(sectionId, alert.title, alert.text, tripsOnSection.first().id)
            }
            // UNKNOWN nie nadpisuje ostatniej znanej oceny.
            if (level != pl.kajakapp.domain.RiskLevel.UNKNOWN) prefs.setLastLevel(sectionId, level.name)
        }
    }

    private companion object {
        const val SOS_WINDOW_MS = 12 * 60 * 60 * 1000L
        const val LOOKAHEAD_DAYS = 3L
    }
}

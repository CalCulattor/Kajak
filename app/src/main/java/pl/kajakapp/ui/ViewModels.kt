package pl.kajakapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.kajakapp.data.ConditionsRepository
import pl.kajakapp.data.FetchStatus
import pl.kajakapp.data.RefreshResult
import pl.kajakapp.data.RiverRepository
import pl.kajakapp.data.TripRepository
import pl.kajakapp.data.db.CheckInEntity
import pl.kajakapp.data.db.GearItemEntity
import pl.kajakapp.data.db.ObstacleEntity
import pl.kajakapp.data.db.ParticipantEntity
import pl.kajakapp.data.db.RiverEntity
import pl.kajakapp.data.db.SectionEntity
import pl.kajakapp.data.db.SectionWithRiver
import pl.kajakapp.data.db.TripEntity
import pl.kajakapp.domain.DataFreshness
import pl.kajakapp.domain.ObstacleRules
import pl.kajakapp.domain.ObstacleType
import pl.kajakapp.domain.RiskAssessment
import pl.kajakapp.domain.RiskAssessor
import pl.kajakapp.domain.WaterReading
import pl.kajakapp.domain.WeatherSnapshot
import pl.kajakapp.util.GeoPoint

class VmFactory<T : ViewModel>(private val producer: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = producer() as V
}

private const val STOP_TIMEOUT_MS = 5_000L

// ---------------------------------------------------------------- Rzeki

data class RiverItem(val river: RiverEntity, val sections: List<SectionEntity>)

class RiversViewModel(rivers: RiverRepository) : ViewModel() {
    /** null = jeszcze się ładuje. */
    val items: StateFlow<List<RiverItem>?> =
        combine(rivers.observeRivers(), rivers.observeAllSections()) { riverList, sections ->
            riverList.map { river ->
                RiverItem(
                    river = river,
                    sections = sections.map { it.section }.filter { it.riverId == river.id }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
}

// ---------------------------------------------------------------- Odcinek

data class ObstacleItem(val entity: ObstacleEntity, val stale: Boolean)

data class SectionUiState(
    val loaded: Boolean = false,
    val section: SectionWithRiver? = null,
    val water: WaterReading? = null,
    val weather: WeatherSnapshot? = null,
    val waterStale: Boolean = false,
    val weatherStale: Boolean = false,
    val obstacles: List<ObstacleItem> = emptyList(),
    val risk: RiskAssessment? = null,
    val refreshing: Boolean = false,
    val message: String? = null
)

class SectionViewModel(
    private val sectionId: Long,
    private val rivers: RiverRepository,
    private val conditions: ConditionsRepository
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val data = combine(
        rivers.observeSection(sectionId),
        conditions.observeWater(sectionId),
        conditions.observeWeather(sectionId),
        rivers.observeObstacles(sectionId)
    ) { section, water, weather, obstacles ->
        val now = System.currentTimeMillis()
        val activeObstacles = obstacles.filter {
            ObstacleRules.isActive(it.confirmations, it.removalVotes)
        }
        val freshWater = water?.takeIf { DataFreshness.isFresh(it, now) }
        val freshWeather = weather?.takeIf { DataFreshness.isFresh(it, now) }

        SectionUiState(
            loaded = true,
            section = section,
            water = water,
            weather = weather,
            waterStale = water != null && freshWater == null,
            weatherStale = weather != null && freshWeather == null,
            obstacles = activeObstacles.map {
                ObstacleItem(it, ObstacleRules.isStale(it.lastVerifiedAt, now))
            },
            risk = if (section == null) {
                null
            } else {
                RiskAssessor.assess(freshWater, freshWeather, activeObstacles.map { it.type })
            }
        )
    }

    val state: StateFlow<SectionUiState> =
        combine(data, refreshing, message) { d, isRefreshing, msg ->
            d.copy(refreshing = isRefreshing, message = msg)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SectionUiState())

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                val current = rivers.observeSection(sectionId).first()
                if (current != null) {
                    message.value = describe(conditions.refresh(current.section, current.riverName))
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun setStation(name: String) {
        viewModelScope.launch {
            rivers.setStation(sectionId, name)
            refresh()
        }
    }

    fun reportObstacle(type: ObstacleType, description: String, point: GeoPoint?) {
        viewModelScope.launch {
            rivers.reportObstacle(sectionId, type, description, point?.lat, point?.lon)
        }
    }

    fun confirmObstacle(id: Long) {
        viewModelScope.launch { rivers.confirmObstacle(id) }
    }

    fun voteObstacleRemoved(id: Long) {
        viewModelScope.launch { rivers.voteObstacleRemoved(id) }
    }

    fun showMessage(text: String) {
        message.value = text
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun describe(result: RefreshResult): String? {
        val parts = buildList {
            when (result.water) {
                FetchStatus.ERROR ->
                    add("Nie udało się pobrać stanu wody (brak sieci?). Pokazuję ostatnie dane.")
                FetchStatus.NOT_FOUND ->
                    add("Nie znaleziono wodowskazu o tej nazwie w danych IMGW.")
                FetchStatus.OK, FetchStatus.NO_STATION -> Unit
            }
            if (result.weather == FetchStatus.ERROR) {
                add("Nie udało się pobrać pogody. Pokazuję ostatnie dane.")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}

// ---------------------------------------------------------------- Spływy

data class TripItem(val trip: TripEntity, val sectionLabel: String?)

class TripsViewModel(
    private val trips: TripRepository,
    rivers: RiverRepository
) : ViewModel() {

    val sections: StateFlow<List<SectionWithRiver>> =
        rivers.observeAllSections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** null = jeszcze się ładuje. */
    val items: StateFlow<List<TripItem>?> =
        combine(trips.observeTrips(), rivers.observeAllSections()) { tripList, sectionList ->
            val labels = sectionList.associate { it.section.id to "${it.riverName}: ${it.section.name}" }
            tripList.map { TripItem(it, it.sectionId?.let(labels::get)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun create(
        title: String,
        sectionId: Long?,
        startDateUtcMillis: Long,
        overnight: Boolean,
        organizer: String,
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = trips.createTrip(title, sectionId, startDateUtcMillis, overnight, organizer)
            onCreated(id)
        }
    }
}

data class TripDetailState(
    val loaded: Boolean = false,
    val trip: TripEntity? = null,
    val sectionLabel: String? = null,
    val participants: List<ParticipantEntity> = emptyList(),
    val gear: List<GearItemEntity> = emptyList(),
    val checkIns: List<CheckInEntity> = emptyList()
)

class TripDetailViewModel(
    private val tripId: Long,
    private val trips: TripRepository,
    rivers: RiverRepository
) : ViewModel() {

    val state: StateFlow<TripDetailState> = combine(
        trips.observeTrip(tripId),
        trips.observeParticipants(tripId),
        trips.observeGear(tripId),
        trips.observeCheckIns(tripId),
        rivers.observeAllSections()
    ) { trip, participants, gear, checkIns, sections ->
        TripDetailState(
            loaded = true,
            trip = trip,
            sectionLabel = trip?.sectionId?.let { id ->
                sections.firstOrNull { it.section.id == id }
                    ?.let { "${it.riverName}: ${it.section.name}" }
            },
            participants = participants,
            gear = gear,
            checkIns = checkIns
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripDetailState())

    fun addParticipant(name: String, carSeats: Int, needsKayak: Boolean) {
        viewModelScope.launch { trips.addParticipant(tripId, name, carSeats, needsKayak) }
    }

    fun removeParticipant(id: Long) {
        viewModelScope.launch { trips.removeParticipant(id) }
    }

    fun addGear(name: String) {
        viewModelScope.launch { trips.addGear(tripId, name) }
    }

    fun addSuggestedGear() {
        val current = state.value
        val trip = current.trip ?: return
        viewModelScope.launch {
            trips.addSuggestedGear(tripId, trip.overnight, current.gear.map { it.name }.toSet())
        }
    }

    fun setGearPacked(id: Long, packed: Boolean) {
        viewModelScope.launch { trips.setGearPacked(id, packed) }
    }

    fun assignGear(id: Long, assignee: String?) {
        viewModelScope.launch { trips.assignGear(id, assignee) }
    }

    fun removeGear(id: Long) {
        viewModelScope.launch { trips.removeGear(id) }
    }

    fun checkIn(personName: String, point: GeoPoint, needsHelp: Boolean) {
        viewModelScope.launch {
            trips.checkIn(tripId, personName, point.lat, point.lon, point.fixAt, needsHelp)
        }
    }

    fun deleteTrip(onDeleted: () -> Unit) {
        viewModelScope.launch {
            trips.deleteTrip(tripId)
            onDeleted()
        }
    }
}

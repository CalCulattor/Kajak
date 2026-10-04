package pl.kajakapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.kajakapp.data.ConditionsRepository
import pl.kajakapp.data.FetchStatus
import pl.kajakapp.data.ForecastStatus
import pl.kajakapp.data.LiveUpdates
import pl.kajakapp.data.RefreshResult
import pl.kajakapp.data.RemoteTripInfo
import pl.kajakapp.data.RiverRepository
import pl.kajakapp.data.ServerSettings
import pl.kajakapp.data.SyncRepository
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
import pl.kajakapp.domain.GearRules
import pl.kajakapp.domain.Difficulty
import pl.kajakapp.domain.ObstacleRules
import pl.kajakapp.domain.ObstacleType
import pl.kajakapp.domain.RiskAssessment
import pl.kajakapp.domain.RiskAssessor
import pl.kajakapp.domain.RiverType
import pl.kajakapp.domain.WaterReading
import pl.kajakapp.domain.WeatherSnapshot
import pl.kajakapp.util.GeoPoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class VmFactory<T : ViewModel>(private val producer: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = producer() as V
}

private const val STOP_TIMEOUT_MS = 5_000L

// ---------------------------------------------------------------- Rzeki

data class RiverItem(val river: RiverEntity, val sections: List<SectionEntity>)

class RiversViewModel(
    private val rivers: RiverRepository,
    private val sync: SyncRepository
) : ViewModel() {
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

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /**
     * Wysyła trasy dodane lokalnie i pobiera trasy innych osób. Przy automatycznym wywołaniu
     * komunikat o błędzie pojawia się tylko wtedy, gdy coś czeka na wysłanie.
     */
    fun refresh(manual: Boolean) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val result = sync.syncRoutes()
                val hasPending = items.value.orEmpty().any { item -> item.sections.any { it.pendingSync } }
                if (result.message.isNotEmpty() && (manual || (!result.ok && hasPending))) {
                    _message.value = result.message
                }
            } finally {
                _syncing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

// ---------------------------------------------------------------- Nowa trasa

class AddRouteViewModel(private val rivers: RiverRepository) : ViewModel() {
    fun save(
        riverName: String,
        region: String,
        riverType: RiverType,
        sectionName: String,
        lengthKm: Double,
        difficulty: Difficulty,
        putIn: String,
        takeOut: String,
        lat: Double,
        lon: Double,
        stationName: String,
        description: String,
        onSaved: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = rivers.addRoute(
                riverName, region, riverType, sectionName, lengthKm, difficulty,
                putIn, takeOut, lat, lon, stationName, description
            )
            onSaved(id)
        }
    }
}

// ---------------------------------------------------------------- Prognoza na termin

/** Wynik sprawdzenia warunków na wybrany termin (ekran odcinka i szczegóły spływu). */
data class ForecastUi(
    val loading: Boolean = false,
    /** Opis terminu, np. "pon. 05.10, 10:00–14:00". */
    val whenText: String? = null,
    val weather: WeatherSnapshot? = null,
    val risk: RiskAssessment? = null,
    /** Powód braku wyniku (termin za daleko, brak sieci…). */
    val message: String? = null
)

private val FORECAST_ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
private val FORECAST_DAY_FORMAT = DateTimeFormatter.ofPattern("EEE dd.MM", Locale.forLanguageTag("pl-PL"))
private val FORECAST_CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** Początek spływu jako czas lokalny; bez godziny przyjmujemy 10:00. */
internal fun tripStart(trip: TripEntity): LocalDateTime {
    val date = Instant.ofEpochMilli(trip.startDateUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val time = runCatching { LocalTime.parse(trip.startTime) }.getOrNull() ?: LocalTime.of(10, 0)
    return date.atTime(time)
}

internal fun forecastDays(): List<LocalDate> = pl.kajakapp.domain.ForecastWindow.days(LocalDate.now(FORECAST_ZONE))

private fun forecastLabel(start: LocalDateTime): String {
    val from = start.withMinute(0)
    val to = from.plusHours(pl.kajakapp.domain.ForecastWindow.DEFAULT_HOURS.toLong())
    return "${FORECAST_DAY_FORMAT.format(from)}, ${FORECAST_CLOCK_FORMAT.format(from)}–${FORECAST_CLOCK_FORMAT.format(to)}"
}

/** Pobiera prognozę na termin i ocenia warunki (pogoda na termin + aktualny wodowskaz i przeszkody). */
internal suspend fun loadForecast(
    conditions: ConditionsRepository,
    lat: Double,
    lon: Double,
    start: LocalDateTime,
    water: WaterReading?,
    obstacles: List<ObstacleType>
): ForecastUi {
    val label = forecastLabel(start)
    val fetch = conditions.forecast(lat, lon, start)
    return when (fetch.status) {
        ForecastStatus.OK -> {
            val weather = fetch.weather
            ForecastUi(
                whenText = label,
                weather = weather,
                risk = RiskAssessor.assess(water, weather, obstacles, forecast = true)
            )
        }
        ForecastStatus.PAST ->
            ForecastUi(whenText = label, message = "Ten termin już minął – wybierz dzisiejszy lub późniejszy.")
        ForecastStatus.TOO_FAR ->
            ForecastUi(whenText = label, message = "Prognoza jest dostępna najwyżej 16 dni naprzód.")
        ForecastStatus.NO_DATA ->
            ForecastUi(whenText = label, message = "Brak prognozy na ten termin.")
        ForecastStatus.ERROR ->
            ForecastUi(whenText = label, message = "Nie udało się pobrać prognozy (brak sieci?).")
    }
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
    val message: String? = null,
    /** Warunki na termin wybrany przez użytkownika (null = jeszcze nie sprawdzano). */
    val forecast: ForecastUi? = null
)

class SectionViewModel(
    private val sectionId: Long,
    private val rivers: RiverRepository,
    private val conditions: ConditionsRepository,
    private val sync: SyncRepository
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val forecast = MutableStateFlow<ForecastUi?>(null)
    private var forecastJob: Job? = null

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
        combine(data, refreshing, message, forecast) { d, isRefreshing, msg, fc ->
            d.copy(refreshing = isRefreshing, message = msg, forecast = fc)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SectionUiState())

    /** Sprawdza warunki na wybrany dzień i godzinę (okno kilku godzin od tej godziny). */
    fun checkForecast(date: LocalDate, hour: Int) {
        val current = state.value
        val section = current.section?.section ?: return
        val water = current.water?.takeIf { DataFreshness.isFresh(it, System.currentTimeMillis()) }
        val obstacles = current.obstacles.map { it.entity.type }
        forecastJob?.cancel()
        forecast.value = ForecastUi(loading = true)
        forecastJob = viewModelScope.launch {
            forecast.value = loadForecast(
                conditions, section.lat, section.lon, date.atTime(hour.coerceIn(0, 23), 0), water, obstacles
            )
        }
    }

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                coroutineScope {
                    val conditionsText = async {
                        val current = rivers.observeSection(sectionId).first()
                        if (current == null) {
                            null
                        } else {
                            describe(conditions.refresh(current.section, current.riverName))
                        }
                    }
                    val syncResult = async { sync.syncObstacles(sectionId) }
                    val parts = listOfNotNull(
                        conditionsText.await(),
                        syncResult.await().takeIf { !it.ok && it.message.isNotEmpty() }?.message
                    )
                    message.value = parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Wysyła świeże zgłoszenia/głosy; przy błędzie dane zostają lokalnie i pójdą przy następnej próbie. */
    private fun pushObstacleChanges() {
        viewModelScope.launch {
            val result = sync.syncObstacles(sectionId)
            if (!result.ok && result.message.isNotEmpty()) {
                message.value = "Zapisano na telefonie. ${result.message}"
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
            pushObstacleChanges()
        }
    }

    fun confirmObstacle(id: Long) {
        viewModelScope.launch {
            rivers.confirmObstacle(id)
            pushObstacleChanges()
        }
    }

    fun voteObstacleRemoved(id: Long) {
        viewModelScope.launch {
            rivers.voteObstacleRemoved(id)
            pushObstacleChanges()
        }
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
                FetchStatus.NO_STATION ->
                    add("Nie znaleziono wodowskazu IMGW w pobliżu tego odcinka – możesz ustawić go ręcznie.")
                FetchStatus.OK -> Unit
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

/** Stan okna „Dołącz do spływu z serwera”. */
data class JoinUiState(
    val loading: Boolean = true,
    val trips: List<RemoteTripInfo> = emptyList(),
    val message: String? = null
)

class TripsViewModel(
    private val trips: TripRepository,
    rivers: RiverRepository,
    private val sync: SyncRepository,
    private val settings: ServerSettings,
    live: LiveUpdates
) : ViewModel() {

    /** Zalogowany użytkownik albo null; jego nazwa jest używana jako imię organizatora. */
    val username: StateFlow<String?> = settings.session
        .map { it?.username }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), settings.session.value?.username)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Po wejściu na listę sprawdza udostępnione spływy (usunięte lub opuszczone znikają z telefonu). */
    fun refresh() {
        viewModelScope.launch {
            val result = sync.syncSharedTrips()
            if (result.message.isNotEmpty()) _message.value = result.message
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** null = okno zamknięte. */
    private val _join = MutableStateFlow<JoinUiState?>(null)
    val join: StateFlow<JoinUiState?> = _join

    init {
        // Gdy ktoś inny doda lub usunie spływ, otwarta lista „Dołącz” odświeża się sama.
        viewModelScope.launch {
            live.tripsChanged.collect {
                val open = _join.value
                if (open != null && !open.loading) {
                    val result = sync.listRemoteTrips()
                    if (_join.value?.loading == false && result.outcome.ok) {
                        _join.value = _join.value?.copy(trips = result.trips)
                    }
                }
            }
        }
    }

    fun openJoin() {
        _join.value = JoinUiState(loading = true)
        viewModelScope.launch {
            val result = sync.listRemoteTrips()
            _join.value = JoinUiState(
                loading = false,
                trips = result.trips,
                message = result.outcome.message.takeIf { !result.outcome.ok && it.isNotEmpty() }
            )
        }
    }

    fun closeJoin() {
        _join.value = null
    }

    fun joinTrip(remoteId: Long, onJoined: (Long) -> Unit) {
        _join.value = _join.value?.copy(loading = true, message = null)
        viewModelScope.launch {
            val result = sync.joinTrip(remoteId)
            val localId = result.tripId
            if (localId != null) {
                _join.value = null
                onJoined(localId)
            } else {
                _join.value = _join.value?.copy(loading = false, message = result.outcome.message)
            }
        }
    }

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
        startTime: String,
        overnight: Boolean,
        organizer: String,
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            // Zalogowany użytkownik jest organizatorem pod nazwą swojego konta.
            val account = settings.session.value?.username
            val id = trips.createTrip(
                title = title,
                sectionId = sectionId,
                startDateUtcMillis = startDateUtcMillis,
                startTime = startTime,
                overnight = overnight,
                organizer = account ?: organizer,
                ownerUsername = account
            )
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
    val checkIns: List<CheckInEntity> = emptyList(),
    val syncing: Boolean = false,
    val message: String? = null,
    /** Zalogowane konto (null = niezalogowany). */
    val currentUser: String? = null,
    /** Kto potwierdza wyposażenie na tym telefonie: konto albo „Ja” w spływie bez logowania. */
    val viewer: String = GearRules.LOCAL_VIEWER,
    /** true dla organizatora (a w spływie tylko lokalnym – zawsze). */
    val isOrganizer: Boolean = true,
    /** true, gdy w spływie jest jeszcze inny organizator, więc organizator może go opuścić. */
    val hasOtherOrganizer: Boolean = false,
    /** Ocena warunków na termin spływu (null = spływ bez przypisanego odcinka). */
    val forecast: ForecastUi? = null
)

private data class ForecastTarget(val sectionId: Long, val lat: Double, val lon: Double, val start: LocalDateTime)

class TripDetailViewModel(
    private val tripId: Long,
    private val trips: TripRepository,
    rivers: RiverRepository,
    private val sync: SyncRepository,
    settings: ServerSettings,
    private val conditions: ConditionsRepository
) : ViewModel() {

    private val syncing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val tripForecast = MutableStateFlow<ForecastUi?>(null)

    init {
        // Ocena pogody na termin spływu odświeża się przy zmianie odcinka, daty albo godziny.
        viewModelScope.launch {
            combine(trips.observeTrip(tripId), rivers.observeAllSections()) { trip, sections ->
                val section = trip?.sectionId?.let { id -> sections.firstOrNull { it.section.id == id }?.section }
                if (trip == null || section == null) {
                    null
                } else {
                    ForecastTarget(section.id, section.lat, section.lon, tripStart(trip))
                }
            }.distinctUntilChanged().collectLatest { target ->
                if (target == null) {
                    tripForecast.value = null
                } else {
                    tripForecast.value = ForecastUi(loading = true)
                    val water = conditions.observeWater(target.sectionId).first()
                        ?.takeIf { DataFreshness.isFresh(it, System.currentTimeMillis()) }
                    val obstacles = rivers.observeObstacles(target.sectionId).first()
                        .filter { ObstacleRules.isActive(it.confirmations, it.removalVotes) }
                        .map { it.type }
                    tripForecast.value = loadForecast(conditions, target.lat, target.lon, target.start, water, obstacles)
                }
            }
        }
    }

    private val data = combine(
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
    }

    val state: StateFlow<TripDetailState> =
        combine(data, syncing, message, settings.session, tripForecast) { d, isSyncing, msg, session, fc ->
            val user = session?.username
            val trip = d.trip
            val me = d.participants.firstOrNull { it.name.equals(user, ignoreCase = true) }
            val organizer = trip != null && (trip.serverId == null || me?.isOrganizer == true)
            val otherOrganizer = d.participants.any { it.isOrganizer && it.id != me?.id }
            d.copy(
                syncing = isSyncing,
                message = msg,
                currentUser = user,
                viewer = user ?: GearRules.LOCAL_VIEWER,
                isOrganizer = organizer,
                hasOtherOrganizer = otherOrganizer,
                forecast = fc
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripDetailState())

    private val isShared: Boolean get() = state.value.trip?.serverId != null

    /** Ręczna synchronizacja; pierwsze wywołanie udostępnia spływ na serwerze. */
    fun syncNow() {
        if (syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            try {
                val result = sync.syncTrip(tripId)
                message.value = result.message.ifEmpty { null }
            } finally {
                syncing.value = false
            }
        }
    }

    /** Po zmianie lokalnej wysyła ją od razu, jeśli spływ jest udostępniony. */
    private fun syncIfShared() {
        if (!isShared) return
        viewModelScope.launch {
            val result = sync.syncTrip(tripId)
            if (!result.ok && result.message.isNotEmpty()) {
                message.value = "Zapisano na telefonie. ${result.message}"
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    fun addParticipant(name: String, carSeats: Int, needsKayak: Boolean) {
        viewModelScope.launch {
            trips.addParticipant(tripId, name, carSeats, needsKayak)
            syncIfShared()
        }
    }

    fun removeParticipant(id: Long) {
        val current = state.value
        val remoteParticipantId = current.participants.firstOrNull { it.id == id }?.serverId
        val remoteTripId = current.trip?.serverId
        viewModelScope.launch {
            trips.removeParticipant(id)
            val result = sync.deleteRemote(remoteTripId, remoteParticipantId, null)
            if (!result.ok && result.message.isNotEmpty()) message.value = result.message
        }
    }

    /** Dodaje wybrane pozycje wyposażenia (nazwa + „required”/„recommended”); robi to organizator. */
    fun addGearItems(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val current = state.value
        if (!current.isOrganizer) return
        viewModelScope.launch {
            trips.addGearItems(tripId, items, current.gear.map { it.name }.toSet())
            syncIfShared()
        }
    }

    /** Uczestnik potwierdza (albo cofa potwierdzenie), że ma dany element – zawsze za siebie. */
    fun toggleGearConfirmed(id: Long) {
        val current = state.value
        val gear = current.gear.firstOrNull { it.id == id } ?: return
        val confirmed = !GearRules.isConfirmed(gear.confirmedBy, current.viewer)
        viewModelScope.launch {
            trips.setGearConfirmed(id, current.viewer, confirmed)
            val result = sync.pushGearConfirmation(id, confirmed)
            if (!result.ok && result.message.isNotEmpty()) {
                message.value = "Zapisano na telefonie. ${result.message}"
            }
        }
    }

    /** Organizator zmienia pozycję na wymaganą albo zalecaną. */
    fun setGearRequirement(id: Long, requirement: String) {
        if (!state.value.isOrganizer) return
        viewModelScope.launch {
            trips.setGearRequirement(id, requirement)
            val result = sync.pushGearRequirement(id)
            if (!result.ok && result.message.isNotEmpty()) {
                message.value = "Zapisano na telefonie. ${result.message}"
            }
        }
    }

    fun removeGear(id: Long) {
        val current = state.value
        val remoteGearId = current.gear.firstOrNull { it.id == id }?.serverId
        val remoteTripId = current.trip?.serverId
        viewModelScope.launch {
            trips.removeGear(id)
            val result = sync.deleteRemote(remoteTripId, null, remoteGearId)
            if (!result.ok && result.message.isNotEmpty()) message.value = result.message
        }
    }

    /** Odwołuje wezwanie pomocy (UI pokazuje przycisk tylko jego autorowi, serwer też to sprawdza). */
    fun cancelHelp(checkInId: Long) {
        viewModelScope.launch {
            val result = sync.cancelHelp(checkInId)
            if (!result.ok && result.message.isNotEmpty()) message.value = result.message
        }
    }

    fun checkIn(personName: String, point: GeoPoint, needsHelp: Boolean) {
        viewModelScope.launch {
            trips.checkIn(tripId, personName, point.lat, point.lon, point.fixAt, needsHelp)
            syncIfShared()
        }
    }

    /**
     * Usuwa spływ z telefonu. Spływ udostępniony najpierw znika z serwera: organizator usuwa go
     * dla wszystkich albo (gdy [leaveOnly], dozwolone przy drugim organizatorze) tylko go opuszcza;
     * pozostali uczestnicy zawsze tylko go opuszczają. Bez połączenia nic nie jest usuwane –
     * nie da się zrezygnować ze spływu tylko lokalnie.
     */
    fun deleteTrip(leaveOnly: Boolean, onDeleted: () -> Unit) {
        viewModelScope.launch {
            val result = sync.removeTripFromServer(tripId, leaveOnly)
            if (result.ok) {
                trips.deleteTrip(tripId)
                onDeleted()
            } else {
                message.value = result.message
            }
        }
    }

    /** Organizator mianuje innego uczestnika (z kontem) organizatorem. */
    fun makeOrganizer(participantId: Long) {
        viewModelScope.launch {
            val result = sync.makeOrganizer(tripId, participantId)
            if (result.message.isNotEmpty()) message.value = result.message
        }
    }

    /** Zmienia własne dane uczestnika (miejsca w aucie, kajak) w spływie udostępnionym. */
    fun updateMyData(carSeats: Int, needsKayak: Boolean) {
        val current = state.value
        val me = current.participants.firstOrNull { it.name.equals(current.currentUser, ignoreCase = true) }
            ?: return
        viewModelScope.launch {
            trips.updateParticipantData(me.id, carSeats, needsKayak)
            val result = sync.pushMyParticipant(tripId)
            if (!result.ok && result.message.isNotEmpty()) {
                message.value = "Zapisano na telefonie. ${result.message}"
            }
        }
    }
}

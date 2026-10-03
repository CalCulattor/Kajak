package pl.kajakapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.kajakapp.data.LiveTrack
import pl.kajakapp.data.ServerSettings
import pl.kajakapp.data.Session
import pl.kajakapp.data.TrackRecorder
import pl.kajakapp.data.TrackRepository
import pl.kajakapp.data.TripRepository
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.data.db.TripEntity
import pl.kajakapp.domain.TrackSummary

private const val TRACK_STOP_TIMEOUT_MS = 5_000L

/** Podsumowanie całej historii użytkownika. */
data class HistoryTotals(
    val count: Int = 0,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0,
    val movingMs: Long = 0,
    val longestM: Double = 0.0,
    val topSpeedKmh: Double = 0.0
) {
    /** Średnia prędkość w ruchu ze wszystkich tras. */
    val avgMovingKmh: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) * 3.6 else 0.0
}

data class HistoryState(
    val tracks: List<TrackEntity> = emptyList(),
    val totals: HistoryTotals = HistoryTotals(),
    /** Trasa w toku, której nikt już nie nagrywa (np. system zamknął aplikację). */
    val interrupted: TrackEntity? = null,
    val live: LiveTrack? = null,
    val trips: List<TripEntity> = emptyList()
)

class HistoryViewModel(
    tracks: TrackRepository,
    private val recorder: TrackRecorder,
    trips: TripRepository,
    settings: ServerSettings
) : ViewModel() {

    val state: StateFlow<HistoryState> = combine(
        tracks.observeFinished(),
        tracks.observeActive(),
        recorder.live,
        trips.observeTrips(),
        settings.session,
        recorder.busy
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val finished = values[0] as List<TrackEntity>
        val active = values[1] as TrackEntity?
        val live = values[2] as LiveTrack?
        @Suppress("UNCHECKED_CAST")
        val tripList = values[3] as List<TripEntity>
        val session = values[4] as Session?
        val busy = values[5] as Boolean
        val user = session?.username
        // Widać trasy nagrane na tym koncie oraz te nagrane bez logowania.
        val mine = finished.filter { it.ownerUsername == null || it.ownerUsername.equals(user, ignoreCase = true) }
        HistoryState(
            tracks = mine,
            totals = HistoryTotals(
                count = mine.size,
                distanceM = mine.sumOf { it.distanceM },
                elapsedMs = mine.sumOf { it.elapsedMs },
                movingMs = mine.sumOf { it.movingMs },
                longestM = mine.maxOfOrNull { it.distanceM } ?: 0.0,
                topSpeedKmh = mine.maxOfOrNull { it.maxSpeedKmh } ?: 0.0
            ),
            interrupted = if (live == null && !busy) active else null,
            live = live,
            trips = tripList
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(TRACK_STOP_TIMEOUT_MS), HistoryState())

    /** Wstrzymuje albo wznawia trasę w toku. */
    fun setPaused(paused: Boolean) {
        viewModelScope.launch { recorder.setPaused(paused) }
    }

    /** Kończy i zapisuje przerwaną trasę; zwraca id zapisanej trasy (albo null, gdy była pusta). */
    fun finishInterrupted(onDone: (Long?) -> Unit) {
        viewModelScope.launch {
            val result = recorder.finish()
            // seq == 0: nic nie było w toku (np. drugie dotknięcie przycisku) – nie ma czego ogłaszać.
            if (result.seq > 0) onDone(result.trackId)
        }
    }
}

data class TrackDetailState(
    val loaded: Boolean = false,
    val track: TrackEntity? = null,
    val summary: TrackSummary? = null
)

class TrackDetailViewModel(
    private val trackId: Long,
    private val repo: TrackRepository
) : ViewModel() {
    private val summary = MutableStateFlow<TrackSummary?>(null)

    init {
        viewModelScope.launch {
            summary.value = try {
                repo.analyze(trackId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackSummary.empty() // pokażemy komunikat o braku danych zamiast zawieszać ekran
            }
        }
    }

    val state: StateFlow<TrackDetailState> = combine(repo.observeTrack(trackId), summary) { track, sum ->
        TrackDetailState(loaded = true, track = track, summary = sum)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(TRACK_STOP_TIMEOUT_MS), TrackDetailState())

    fun rename(title: String) {
        viewModelScope.launch { repo.rename(trackId, title) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repo.delete(trackId)
            onDeleted()
        }
    }
}

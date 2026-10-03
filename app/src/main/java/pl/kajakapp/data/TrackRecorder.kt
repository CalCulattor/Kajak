package pl.kajakapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.kajakapp.data.db.TrackDao
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.data.db.TrackPointEntity
import pl.kajakapp.domain.TrackAccumulator
import pl.kajakapp.domain.TrackPoint

/** Stan nagrywanej właśnie trasy, pokazywany na żywo. */
data class LiveTrack(
    val trackId: Long,
    val title: String,
    val startedAt: Long,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    /** Bieżąca prędkość w km/h (0 na postoju). */
    val speedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val readings: Int = 0,
    val lastFixAt: Long? = null,
    val lastAccuracyM: Float? = null,
    /** false, gdy GPS jest wyłączony w telefonie. */
    val gpsEnabled: Boolean = true
)

/**
 * Wynik zakończenia: [trackId] == null oznacza, że trasa była zbyt krótka i została odrzucona.
 * [seq] rośnie przy każdym zakończeniu, dzięki czemu kolejne wyniki są zawsze różne.
 */
data class FinishResult(val trackId: Long?, val seq: Long = 0)

/**
 * Zapisuje trasę na bieżąco (każdy odczyt trafia od razu do bazy, więc nic nie ginie przy
 * zamknięciu aplikacji) i prowadzi podgląd na żywo. Odczyty dostarcza usługa [pl.kajakapp.tracking.TrackingService].
 */
class TrackRecorder(
    private val dao: TrackDao,
    private val settings: ServerSettings,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private var acc = TrackAccumulator()
    private var trackId: Long? = null
    private var title: String = ""
    private var startedAt = 0L
    private var gpsEnabled = true
    private var lastAccuracy: Float? = null
    private var maxLiveSpeed = 0.0

    private val _live = MutableStateFlow<LiveTrack?>(null)
    val live: StateFlow<LiveTrack?> = _live

    private val _lastResult = MutableStateFlow<FinishResult?>(null)

    /** Ostatnio zakończona trasa (null, dopóki żadna nie została zakończona w tym uruchomieniu aplikacji). */
    val lastResult: StateFlow<FinishResult?> = _lastResult

    private var resultSeq = 0L

    private val _busy = MutableStateFlow(false)

    /** true, gdy trasa jest właśnie zakładana lub wznawiana (w bazie już jest, ale podgląd jeszcze nie). */
    val busy: StateFlow<Boolean> = _busy

    /** Rozpoczyna nową trasę albo (gdy jakaś jest w toku) kontynuuje tę w toku. Zwraca id trasy. */
    suspend fun begin(title: String, tripId: Long?, tripTitle: String?): Long = mutex.withLock {
        _busy.value = true
        try {
            beginLocked(title, tripId, tripTitle)
        } finally {
            _busy.value = false
        }
    }

    private suspend fun beginLocked(title: String, tripId: Long?, tripTitle: String?): Long {
        trackId?.let { return it }
        dao.activeTrack()?.let { return resumeLocked(it) }
        val now = clock()
        val entity = TrackEntity(
            title = title.trim().ifBlank { "Spływ ${pl.kajakapp.util.Fmt.dateTime(now)}" },
            ownerUsername = settings.session.value?.username,
            tripId = tripId,
            tripTitle = tripTitle,
            startedAt = now
        )
        val id = dao.insertTrack(entity)
        reset(id, entity.title, now)
        publish(now)
        return id
    }

    /** Wznawia trasę przerwaną (np. po zamknięciu aplikacji przez system). False, gdy nic nie było w toku. */
    suspend fun resumeActive(): Boolean = mutex.withLock {
        if (trackId != null) return@withLock true
        _busy.value = true
        try {
            val active = dao.activeTrack() ?: return@withLock false
            resumeLocked(active)
            true
        } finally {
            _busy.value = false
        }
    }

    private suspend fun resumeLocked(t: TrackEntity): Long {
        reset(t.id, t.title, t.startedAt)
        for (p in dao.pointsOf(t.id)) acc.add(TrackPoint(p.time, p.lat, p.lon, p.accuracy))
        maxLiveSpeed = if (acc.distanceM > 0) acc.summary().maxSpeedKmh else 0.0
        publish(acc.lastFixAt ?: clock())
        return t.id
    }

    private fun reset(id: Long, title: String, startedAt: Long) {
        acc = TrackAccumulator()
        trackId = id
        this.title = title
        this.startedAt = startedAt
        lastAccuracy = null
        maxLiveSpeed = 0.0
    }

    /**
     * Zapisuje odczyt GPS i odświeża podgląd. Wołać po kolei (jedna kolejka).
     * Zwraca false, gdy żadna trasa nie jest w toku (usługa powinna się wtedy zatrzymać).
     */
    suspend fun onFix(p: TrackPoint): Boolean = mutex.withLock {
        val id = trackId ?: return@withLock false
        dao.insertPoint(TrackPointEntity(trackId = id, time = p.time, lat = p.lat, lon = p.lon, accuracy = p.accuracy))
        acc.add(p)
        lastAccuracy = p.accuracy
        val speed = acc.currentSpeedMs(p.time) * 3.6
        if (speed > maxLiveSpeed) maxLiveSpeed = speed
        publish(p.time)
        true
    }

    fun setGpsEnabled(enabled: Boolean) {
        gpsEnabled = enabled
        _live.update { it?.copy(gpsEnabled = enabled) }
    }

    private fun publish(now: Long) {
        val id = trackId ?: return
        _live.value = LiveTrack(
            trackId = id,
            title = title,
            startedAt = startedAt,
            distanceM = acc.distanceM,
            movingMs = acc.movingMs,
            speedKmh = acc.currentSpeedMs(now) * 3.6,
            maxSpeedKmh = maxLiveSpeed,
            readings = acc.usedPoints,
            lastFixAt = acc.lastFixAt,
            lastAccuracyM = lastAccuracy,
            gpsEnabled = gpsEnabled
        )
    }

    /**
     * Kończy trasę i zapisuje podsumowanie. Trasy bez żadnego ruchu (mniej niż [MIN_SAVE_DISTANCE_M])
     * są odrzucane. Zwraca id zapisanej trasy albo null.
     */
    suspend fun finish(): FinishResult = mutex.withLock {
        _busy.value = true
        try {
            finishLocked()
        } finally {
            _busy.value = false
        }
    }

    private suspend fun finishLocked(): FinishResult {
        val id = trackId ?: dao.activeTrack()?.also { resumeLocked(it) }?.id
            // Nic nie było w toku (np. podwójne „Zakończ”) – nie ma czego zapisywać ani ogłaszać.
            ?: return FinishResult(null)
        val entity = dao.getTrack(id)
        val summary = acc.summary()
        val saved = if (entity == null || summary.distanceM < MIN_SAVE_DISTANCE_M || !summary.hasData) {
            dao.deleteTrack(id)
            FinishResult(null)
        } else {
            dao.updateTrack(
                entity.copy(
                    endedAt = maxOf(summary.endedAt, entity.startedAt),
                    distanceM = summary.distanceM,
                    elapsedMs = summary.elapsedMs,
                    movingMs = summary.movingMs,
                    maxSpeedKmh = summary.maxSpeedKmh,
                    pointCount = summary.usedPoints
                )
            )
            FinishResult(id)
        }
        val result = saved.copy(seq = ++resultSeq)
        trackId = null
        acc = TrackAccumulator()
        _live.value = null
        _lastResult.value = result
        return result
    }

    companion object {
        const val MIN_SAVE_DISTANCE_M = 30.0
    }
}

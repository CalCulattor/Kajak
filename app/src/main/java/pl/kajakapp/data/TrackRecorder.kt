package pl.kajakapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.kajakapp.data.db.TrackDao
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.data.db.TrackPointEntity
import pl.kajakapp.domain.PathPoint
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
    val gpsEnabled: Boolean = true,
    /** Łączny czas dotychczasowych pauz (zakończonych). */
    val pausedMs: Long = 0,
    /** Kiedy rozpoczęła się trwająca pauza; null, gdy trasa nie jest wstrzymana. */
    val pausedSince: Long? = null,
    /** Lokalny spływ, do którego przypisano trasę (null = brak). */
    val tripId: Long? = null
) {
    val paused: Boolean get() = pausedSince != null

    /** Czas trasy bez pauz w chwili [now] (przy pauzie zatrzymany). */
    fun activeElapsedMs(now: Long): Long = ((pausedSince ?: now) - startedAt - pausedMs).coerceAtLeast(0)
}

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
    private var tripId: Long? = null
    private var gpsEnabled = true
    private var lastAccuracy: Float? = null
    private var maxLiveSpeed = 0.0

    // Pauza: odczyty GPS w czasie pauzy są pomijane, a jej czas nie wlicza się do czasu trasy.
    private var pausedSince: Long? = null

    /** Wszystkie pauzy – do licznika czasu na żywo. */
    private var pausedTotal = 0L

    /** Pauzy zakończone, po których nie było jeszcze przyjętego odczytu (mogą leżeć za końcem trasy). */
    private var pausePending = 0L

    /** Pauzy leżące między pierwszym a ostatnim przyjętym odczytem – tylko one skracają czas trasy. */
    private var pausedInRange = 0L

    private val _live = MutableStateFlow<LiveTrack?>(null)
    val live: StateFlow<LiveTrack?> = _live

    private val path = ArrayList<PathPoint>()
    private val _livePath = MutableStateFlow<List<PathPoint>>(emptyList())

    /** Dotychczasowy ślad trasy w toku (do rysowania na mapie); puste, gdy nic nie jest nagrywane. */
    val livePath: StateFlow<List<PathPoint>> = _livePath

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
        reset(id, entity.title, now, tripId)
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
        reset(t.id, t.title, t.startedAt, t.tripId)
        for (p in dao.pointsOf(t.id)) {
            if (acc.add(TrackPoint(p.time, p.lat, p.lon, p.accuracy))) appendPath(p.lat, p.lon)
        }
        _livePath.value = ArrayList(path)
        maxLiveSpeed = if (acc.distanceM > 0) acc.summary().maxSpeedKmh else 0.0
        publish(acc.lastFixAt ?: clock())
        return t.id
    }

    private fun reset(id: Long, title: String, startedAt: Long, tripId: Long?) {
        this.tripId = tripId
        acc = TrackAccumulator()
        trackId = id
        path.clear()
        _livePath.value = emptyList()
        this.title = title
        this.startedAt = startedAt
        lastAccuracy = null
        maxLiveSpeed = 0.0
        pausedSince = null
        pausedTotal = 0L
        pausePending = 0L
        pausedInRange = 0L
    }

    /**
     * Zapisuje odczyt GPS i odświeża podgląd. Wołać po kolei (jedna kolejka).
     * Zwraca false, gdy żadna trasa nie jest w toku (usługa powinna się wtedy zatrzymać).
     */
    suspend fun onFix(p: TrackPoint): Boolean = mutex.withLock {
        val id = trackId ?: return@withLock false
        if (pausedSince != null) return@withLock true // trasa wstrzymana – odczyt pomijamy
        dao.insertPoint(TrackPointEntity(trackId = id, time = p.time, lat = p.lat, lon = p.lon, accuracy = p.accuracy))
        // Na mapie rysujemy tylko odczyty, które przyjął też algorytm trasy (bez skoków i słabych fixów).
        val lastBefore = acc.lastFixAt
        val created = acc.add(p)
        if (acc.lastFixAt != lastBefore) {
            // Odczyt przyjęty: oczekujące pauzy leżą teraz między odczytami (pauza przed pierwszym – poza trasą).
            if (lastBefore != null) pausedInRange += pausePending
            pausePending = 0L
        }
        if (created && appendPath(p.lat, p.lon)) _livePath.value = ArrayList(path)
        lastAccuracy = p.accuracy
        val speed = acc.currentSpeedMs(p.time) * 3.6
        if (speed > maxLiveSpeed) maxLiveSpeed = speed
        publish(p.time)
        true
    }

    /** Dopisuje punkt do śladu na mapie, pomijając punkty bliższe niż kilka metrów od poprzedniego. */
    private fun appendPath(lat: Double, lon: Double): Boolean {
        val last = path.lastOrNull()
        if (last != null) {
            val dLat = (lat - last.lat) * 111_320.0
            val dLon = (lon - last.lon) * 111_320.0 * Math.cos(Math.toRadians(lat))
            if (dLat * dLat + dLon * dLon < MAP_MIN_STEP_M * MAP_MIN_STEP_M) return false
        }
        path.add(PathPoint(lat, lon))
        return true
    }

    /** Wstrzymuje albo wznawia trasę w toku. Bez trasy w toku nic nie robi. */
    suspend fun setPaused(paused: Boolean) = mutex.withLock {
        if (trackId == null) return@withLock
        val since = pausedSince
        if (paused && since == null) {
            pausedSince = clock()
        } else if (!paused && since != null) {
            val length = (clock() - since).coerceAtLeast(0)
            pausedTotal += length
            pausePending += length
            pausedSince = null
        } else {
            return@withLock
        }
        publish(clock())
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
            speedKmh = if (pausedSince != null) 0.0 else acc.currentSpeedMs(now) * 3.6,
            maxSpeedKmh = maxLiveSpeed,
            readings = acc.usedPoints,
            lastFixAt = acc.lastFixAt,
            lastAccuracyM = lastAccuracy,
            gpsEnabled = gpsEnabled,
            pausedMs = pausedTotal,
            pausedSince = pausedSince,
            tripId = tripId
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
        val savedPause = pausedInRange
        val saved = if (entity == null || summary.distanceM < MIN_SAVE_DISTANCE_M || !summary.hasData) {
            dao.deleteTrack(id)
            FinishResult(null)
        } else {
            dao.updateTrack(
                entity.copy(
                    endedAt = maxOf(summary.endedAt, entity.startedAt),
                    distanceM = summary.distanceM,
                    // Pauza po ostatnim odczycie nie leży między odczytami, więc jej nie odejmujemy.
                    elapsedMs = (summary.elapsedMs - savedPause).coerceAtLeast(0),
                    pausedMs = savedPause,
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
        path.clear()
        _livePath.value = emptyList()
        _live.value = null
        _lastResult.value = result
        return result
    }

    companion object {
        const val MIN_SAVE_DISTANCE_M = 30.0
        private const val MAP_MIN_STEP_M = 3.0
    }
}

package pl.kajakapp.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pojedynczy odczyt GPS zapisany podczas trasy. [accuracy] to szacowany błąd w metrach. */
data class TrackPoint(val time: Long, val lat: Double, val lon: Double, val accuracy: Float? = null)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Odległość po powierzchni Ziemi (wzór haversine), w metrach. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }
}

/** Jeden przejechany kilometr (ostatni może być niepełny – wtedy [distanceM] < 1000). */
data class KmSplit(val index: Int, val durationMs: Long, val distanceM: Double, val speedKmh: Double) {
    val isPartial: Boolean get() = distanceM < 999.5
}

/** Prędkość w danym momencie trasy ([offsetMs] od jej początku) – do wykresu. */
data class SpeedSample(val offsetMs: Long, val speedKmh: Double)

data class PathPoint(val lat: Double, val lon: Double)

/** Wynik analizy trasy. Wszystkie prędkości w km/h, czasy w milisekundach, dystans w metrach. */
data class TrackSummary(
    val startedAt: Long,
    val endedAt: Long,
    /** Czas od pierwszego do ostatniego odczytu (razem z postojami). */
    val elapsedMs: Long,
    /** Czas faktycznego płynięcia (bez postojów). */
    val movingMs: Long,
    val stoppedMs: Long,
    /** Liczba dłuższych postojów (co najmniej [TrackAccumulator.STOP_MIN_MS]). */
    val stops: Int,
    val distanceM: Double,
    /** Dystans / czas całkowity. */
    val avgSpeedKmh: Double,
    /** Dystans / czas płynięcia. */
    val avgMovingSpeedKmh: Double,
    val maxSpeedKmh: Double,
    /** Tempo w minutach na kilometr (w ruchu); null, gdy nie ma ruchu. */
    val paceMinPerKm: Double?,
    val splits: List<KmSplit>,
    val speedProfile: List<SpeedSample>,
    /** Uproszczony ślad do narysowania na planszy. */
    val path: List<PathPoint>,
    val usedPoints: Int,
    val droppedPoints: Int
) {
    val distanceKm: Double get() = distanceM / 1000.0
    val hasData: Boolean get() = elapsedMs > 0 && path.size >= 2

    companion object {
        fun empty(at: Long = 0L) = TrackSummary(
            at, at, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, null,
            emptyList(), emptyList(), emptyList(), 0, 0
        )
    }
}

/**
 * Liczy statystyki trasy punkt po punkcie. Ta sama logika służy do podglądu na żywo i do
 * końcowej analizy, więc liczby się zgadzają.
 *
 * Odczyty GPS są filtrowane:
 *  - zbyt niedokładne (> [MAX_ACCURACY_M]) i nieuporządkowane w czasie są odrzucane;
 *  - nowy punkt trasy powstaje dopiero po przesunięciu o co najmniej [MIN_STEP_M] (lub o błąd
 *    odczytu), dzięki czemu „drżenie” GPS na postoju nie nalicza dystansu;
 *  - skoki szybsze niż [MAX_SPEED_MS] są uznawane za błąd GPS (po [MAX_REJECTED] takich z rzędu
 *    przyjmujemy nową pozycję, ale bez doliczania dystansu);
 *  - odcinki wolniejsze niż [MOVING_SPEED_MS] oraz przerwy dłuższe niż [GAP_MS] liczą się jako postój.
 */
class TrackAccumulator {
    private class Anchor(
        val time: Long,
        val lat: Double,
        val lon: Double,
        /** Dystans przebyty w ruchu do tego punktu. */
        val cumDist: Double,
        val cumMoving: Long
    )

    private class Segment(val start: Long, val end: Long, val distM: Double, val moving: Boolean)

    private val anchors = ArrayList<Anchor>()
    private val segments = ArrayList<Segment>()
    private var firstTime: Long? = null
    private var lastTime: Long? = null
    private var rejectedInRow = 0
    var usedPoints = 0
        private set
    var droppedPoints = 0
        private set

    val distanceM: Double get() = anchors.lastOrNull()?.cumDist ?: 0.0
    val movingMs: Long get() = anchors.lastOrNull()?.cumMoving ?: 0L
    val elapsedMs: Long get() = (lastTime ?: 0L) - (firstTime ?: 0L)
    val startedAt: Long? get() = firstTime
    val lastFixAt: Long? get() = lastTime

    /** Dodaje odczyt; true, gdy utworzył nowy punkt trasy. */
    fun add(p: TrackPoint): Boolean {
        val acc = p.accuracy
        if (acc != null && acc > MAX_ACCURACY_M) {
            droppedPoints++
            return false
        }
        val last = lastTime
        if (last != null && p.time <= last) {
            droppedPoints++
            return false
        }
        val anchor = anchors.lastOrNull()
        if (anchor == null) {
            firstTime = p.time
            lastTime = p.time
            usedPoints++
            anchors += Anchor(p.time, p.lat, p.lon, 0.0, 0L)
            return true
        }

        val dtMs = p.time - anchor.time
        val d = Geo.distanceM(anchor.lat, anchor.lon, p.lat, p.lon)
        val speed = d / (dtMs / 1000.0)
        val jump = speed > MAX_SPEED_MS
        if (jump && rejectedInRow < MAX_REJECTED) {
            rejectedInRow++
            droppedPoints++
            return false
        }
        // Odczyt jest przyjęty (nawet jeśli to postój bez nowego punktu).
        lastTime = p.time
        usedPoints++
        if (d < max(MIN_STEP_M, (acc ?: 0f).toDouble())) return false

        val forcedJump = jump
        rejectedInRow = 0
        val moving = !forcedJump && dtMs <= GAP_MS && speed >= MOVING_SPEED_MS
        segments += Segment(anchor.time, p.time, d, moving)
        anchors += Anchor(
            p.time, p.lat, p.lon,
            anchor.cumDist + if (moving) d else 0.0,
            anchor.cumMoving + if (moving) dtMs else 0L
        )
        return true
    }

    /**
     * Bieżąca prędkość (m/s) w chwili [now]: dystans z ostatnich ~[SPEED_WINDOW_MS] podzielony przez czas.
     * Na postoju (brak nowych punktów trasy) wynosi 0.
     */
    fun currentSpeedMs(now: Long): Double {
        val lastAnchor = anchors.lastOrNull() ?: return 0.0
        if (anchors.size < 2) return 0.0
        var start = lastAnchor
        for (i in anchors.indices.reversed()) {
            start = anchors[i]
            if (start.time <= now - SPEED_WINDOW_MS) break
        }
        val dt = now - start.time
        if (dt <= 0) return 0.0
        return ((lastAnchor.cumDist - start.cumDist) / (dt / 1000.0)).coerceIn(0.0, MAX_SPEED_MS)
    }

    fun summary(): TrackSummary {
        val first = firstTime ?: return TrackSummary.empty()
        val last = lastTime ?: first
        val elapsed = last - first
        if (anchors.size < 2 || elapsed <= 0) {
            return TrackSummary.empty(first).copy(
                endedAt = last, elapsedMs = elapsed, stoppedMs = elapsed,
                usedPoints = usedPoints, droppedPoints = droppedPoints,
                path = anchors.map { PathPoint(it.lat, it.lon) }
            )
        }
        val dist = distanceM
        val moving = movingMs
        val avg = dist / (elapsed / 1000.0) * MS_TO_KMH
        val avgMoving = if (moving > 0) dist / (moving / 1000.0) * MS_TO_KMH else 0.0
        return TrackSummary(
            startedAt = first,
            endedAt = last,
            elapsedMs = elapsed,
            movingMs = moving,
            stoppedMs = (elapsed - moving).coerceAtLeast(0),
            stops = countStops(last),
            distanceM = dist,
            avgSpeedKmh = avg,
            avgMovingSpeedKmh = avgMoving,
            maxSpeedKmh = maxSpeedKmh(avgMoving),
            paceMinPerKm = if (avgMoving >= MIN_PACE_KMH) 60.0 / avgMoving else null,
            splits = splits(first),
            speedProfile = speedProfile(first, elapsed),
            path = sampledPath(),
            usedPoints = usedPoints,
            droppedPoints = droppedPoints
        )
    }

    /** Liczy ciągłe postoje (kolejne nieruchome odcinki + końcówka bez nowych punktów). */
    private fun countStops(last: Long): Int {
        var count = 0
        var runStart: Long? = null
        for (s in segments) {
            if (s.moving) {
                if (runStart != null && s.start - runStart >= STOP_MIN_MS) count++
                runStart = null
            } else if (runStart == null) {
                runStart = s.start
            }
        }
        // Końcówka: od ostatniego punktu trasy do ostatniego odczytu.
        val tailStart = runStart ?: anchors.last().time
        if (last - tailStart >= STOP_MIN_MS) count++
        return count
    }

    private fun maxSpeedKmh(fallback: Double): Double {
        var best = 0.0
        var i = 0
        for (j in anchors.indices) {
            // Okno co najmniej SMOOTH_MS, żeby pojedynczy skok GPS nie dawał rekordu.
            while (i + 1 < j && anchors[j].time - anchors[i + 1].time >= SMOOTH_MS) i++
            val dt = anchors[j].time - anchors[i].time
            if (j > i && dt >= SMOOTH_MS) {
                val v = (anchors[j].cumDist - anchors[i].cumDist) / (dt / 1000.0)
                best = max(best, v)
            }
        }
        val kmh = if (best > 0.0) best * MS_TO_KMH else fallback
        return min(kmh, MAX_SPEED_MS * MS_TO_KMH)
    }

    private fun splits(first: Long): List<KmSplit> {
        val out = ArrayList<KmSplit>()
        var target = 1000.0
        var splitStart = first
        var progressTime = first
        for (i in 1 until anchors.size) {
            val a = anchors[i - 1]
            val b = anchors[i]
            if (b.cumDist > a.cumDist) progressTime = b.time
            while (b.cumDist >= target && b.cumDist > a.cumDist) {
                val frac = (target - a.cumDist) / (b.cumDist - a.cumDist)
                val t = a.time + ((b.time - a.time) * frac).toLong()
                val dur = (t - splitStart).coerceAtLeast(1)
                out += KmSplit(out.size + 1, dur, 1000.0, 1000.0 / (dur / 1000.0) * MS_TO_KMH)
                splitStart = t
                target += 1000.0
            }
        }
        val rest = distanceM - (target - 1000.0)
        if (rest >= MIN_PARTIAL_SPLIT_M) {
            val dur = (progressTime - splitStart).coerceAtLeast(1)
            out += KmSplit(out.size + 1, dur, rest, rest / (dur / 1000.0) * MS_TO_KMH)
        }
        return out
    }

    private fun speedProfile(first: Long, elapsed: Long): List<SpeedSample> {
        val buckets = (elapsed / PROFILE_BUCKET_MS).toInt().coerceIn(1, MAX_PROFILE_BUCKETS)
        val width = elapsed.toDouble() / buckets
        val dist = DoubleArray(buckets)
        for (s in segments) {
            if (!s.moving) continue
            val sStart = (s.start - first).toDouble()
            val sLen = (s.end - s.start).toDouble()
            val from = (sStart / width).toInt().coerceIn(0, buckets - 1)
            val to = ((sStart + sLen) / width).toInt().coerceIn(0, buckets - 1)
            for (b in from..to) {
                val overlap = min(sStart + sLen, (b + 1) * width) - max(sStart, b * width)
                if (overlap > 0) dist[b] += s.distM * overlap / sLen
            }
        }
        return List(buckets) { b ->
            SpeedSample(((b + 0.5) * width).toLong(), dist[b] / (width / 1000.0) * MS_TO_KMH)
        }
    }

    private fun sampledPath(): List<PathPoint> {
        if (anchors.size <= MAX_PATH_POINTS) return anchors.map { PathPoint(it.lat, it.lon) }
        val step = anchors.size.toDouble() / MAX_PATH_POINTS
        val out = ArrayList<PathPoint>(MAX_PATH_POINTS + 1)
        var pos = 0.0
        while (pos < anchors.size - 1) {
            val a = anchors[pos.toInt()]
            out += PathPoint(a.lat, a.lon)
            pos += step
        }
        val l = anchors.last()
        out += PathPoint(l.lat, l.lon)
        return out
    }

    companion object {
        const val MAX_ACCURACY_M = 50f
        const val MIN_STEP_M = 5.0
        const val MAX_SPEED_MS = 9.0 // ok. 32 km/h – szybciej kajakiem się nie płynie
        const val MAX_REJECTED = 5
        const val MOVING_SPEED_MS = 0.5
        const val GAP_MS = 5 * 60_000L
        const val STOP_MIN_MS = 2 * 60_000L
        const val SPEED_WINDOW_MS = 15_000L
        const val SMOOTH_MS = 10_000L
        const val MIN_PARTIAL_SPLIT_M = 100.0
        const val PROFILE_BUCKET_MS = 30_000L
        const val MAX_PROFILE_BUCKETS = 60
        const val MAX_PATH_POINTS = 500
        const val MIN_PACE_KMH = 0.5
        private const val MS_TO_KMH = 3.6
    }
}

object TrackAnalyzer {
    /** Analiza zapisanej trasy (punkty w dowolnej kolejności). */
    fun analyze(points: List<TrackPoint>): TrackSummary {
        val acc = TrackAccumulator()
        for (p in points.sortedBy { it.time }) acc.add(p)
        return acc.summary()
    }
}

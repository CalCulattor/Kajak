package pl.kajakapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import pl.kajakapp.data.db.TrackDao
import pl.kajakapp.data.db.TrackEntity
import pl.kajakapp.domain.PathPoint
import pl.kajakapp.domain.TrackAnalyzer
import pl.kajakapp.domain.TrackPoint
import pl.kajakapp.domain.TrackSummary

/** Odczyt historii nagranych tras i ich analiza. */
class TrackRepository(private val dao: TrackDao) {
    fun observeFinished(): Flow<List<TrackEntity>> = dao.observeFinished()

    fun observeActive(): Flow<TrackEntity?> = dao.observeActive()

    fun observeTrack(id: Long): Flow<TrackEntity?> = dao.observeTrack(id)

    /** Pełna analiza zapisanej trasy (liczona z punktów GPS). */
    suspend fun analyze(id: Long): TrackSummary = withContext(Dispatchers.Default) {
        val points = dao.pointsOf(id).map { TrackPoint(it.time, it.lat, it.lon, it.accuracy) }
        TrackAnalyzer.analyze(points)
    }

    /**
     * Ślady wielu tras (do mapy całej historii). Długie trasy są rzedzone do ok. [MAX_POINTS_PER_TRACK]
     * punktów – na mapie przeglądowej różnicy nie widać, a rysowanie pozostaje płynne.
     */
    suspend fun routesOf(trackIds: List<Long>): List<List<PathPoint>> = withContext(Dispatchers.Default) {
        val grouped = HashMap<Long, MutableList<PathPoint>>()
        // SQLite ogranicza liczbę parametrów zapytania, więc pytamy partiami.
        for (chunk in trackIds.chunked(CHUNK)) {
            for (row in dao.routePointsOf(chunk)) {
                grouped.getOrPut(row.trackId) { ArrayList() }.add(PathPoint(row.lat, row.lon))
            }
        }
        trackIds.mapNotNull { id -> grouped[id]?.let { thin(it) } }.filter { it.size >= 2 }
    }

    private fun thin(points: List<PathPoint>): List<PathPoint> {
        if (points.size <= MAX_POINTS_PER_TRACK) return points
        val step = points.size.toDouble() / MAX_POINTS_PER_TRACK
        val out = ArrayList<PathPoint>(MAX_POINTS_PER_TRACK + 1)
        var i = 0.0
        while (i < points.size) {
            out += points[i.toInt()]
            i += step
        }
        // Koniec trasy zawsze zostaje, żeby linia nie urywała się przed metą.
        if (out.last() != points.last()) out += points.last()
        return out
    }

    suspend fun rename(id: Long, title: String) {
        val clean = title.trim().take(MAX_TITLE)
        if (clean.isNotEmpty()) dao.rename(id, clean)
    }

    suspend fun delete(id: Long) = dao.deleteTrack(id)

    companion object {
        const val MAX_TITLE = 80
        private const val CHUNK = 400
        private const val MAX_POINTS_PER_TRACK = 400
    }
}

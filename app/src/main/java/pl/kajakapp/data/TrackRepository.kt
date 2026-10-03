package pl.kajakapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import pl.kajakapp.data.db.TrackDao
import pl.kajakapp.data.db.TrackEntity
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

    suspend fun rename(id: Long, title: String) {
        val clean = title.trim().take(MAX_TITLE)
        if (clean.isNotEmpty()) dao.rename(id, clean)
    }

    suspend fun delete(id: Long) = dao.deleteTrack(id)

    companion object {
        const val MAX_TITLE = 80
    }
}

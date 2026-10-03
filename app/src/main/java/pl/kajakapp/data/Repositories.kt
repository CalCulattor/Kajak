package pl.kajakapp.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import pl.kajakapp.data.db.AppDatabase
import pl.kajakapp.data.db.CheckInEntity
import pl.kajakapp.data.db.GearItemEntity
import pl.kajakapp.data.db.ObstacleEntity
import pl.kajakapp.data.db.ParticipantEntity
import pl.kajakapp.data.db.RiverEntity
import pl.kajakapp.data.db.SectionEntity
import pl.kajakapp.data.db.SectionWithRiver
import pl.kajakapp.data.db.TripEntity
import pl.kajakapp.domain.Difficulty
import pl.kajakapp.domain.ObstacleType
import pl.kajakapp.domain.RiverType

class RiverRepository(private val db: AppDatabase) {
    private val dao = db.riverDao()
    private val obstacleDao = db.obstacleDao()

    fun observeRivers(): Flow<List<RiverEntity>> = dao.observeRivers()
    fun observeSections(riverId: Long): Flow<List<SectionEntity>> = dao.observeSections(riverId)
    fun observeSection(sectionId: Long): Flow<SectionWithRiver?> = dao.observeSection(sectionId)
    fun observeAllSections(): Flow<List<SectionWithRiver>> = dao.observeAllSections()

    suspend fun setStation(sectionId: Long, stationName: String?) {
        dao.updateStation(sectionId, stationName?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * Zapisuje nową trasę (odcinek) lokalnie i oznacza ją do wysłania na serwer.
     * Rzeka o takiej samej nazwie i regionie jest używana ponownie.
     */
    suspend fun addRoute(
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
        description: String
    ): Long = db.withTransaction {
        val name = riverName.trim()
        val area = region.trim()
        val riverId = dao.findRiver(name, area)?.id
            ?: dao.insertRiver(RiverEntity(name = name, region = area, type = riverType, description = ""))
        dao.insertSection(
            SectionEntity(
                riverId = riverId,
                name = sectionName.trim(),
                lengthKm = lengthKm,
                difficulty = difficulty,
                putIn = putIn.trim(),
                takeOut = takeOut.trim(),
                lat = lat,
                lon = lon,
                stationName = stationName.trim().takeIf { it.isNotEmpty() },
                description = description.trim(),
                pendingSync = true
            )
        )
    }

    fun observeObstacles(sectionId: Long): Flow<List<ObstacleEntity>> =
        obstacleDao.observeForSection(sectionId)

    suspend fun reportObstacle(
        sectionId: Long,
        type: ObstacleType,
        description: String,
        lat: Double?,
        lon: Double?
    ) {
        val now = System.currentTimeMillis()
        obstacleDao.insert(
            ObstacleEntity(
                sectionId = sectionId,
                type = type,
                description = description.trim(),
                lat = lat,
                lon = lon,
                reportedAt = now,
                lastVerifiedAt = now
            )
        )
    }

    suspend fun confirmObstacle(id: Long) = obstacleDao.confirm(id, System.currentTimeMillis())

    suspend fun voteObstacleRemoved(id: Long) =
        obstacleDao.voteRemoved(id, System.currentTimeMillis())

    /** Wstawia dane startowe tylko do pustej bazy. */
    suspend fun seedIfEmpty() {
        if (dao.countRivers() > 0) return
        db.withTransaction {
            if (dao.countRivers() > 0) return@withTransaction
            for (seed in SeedData.rivers) {
                val riverId = dao.insertRiver(seed.river)
                dao.insertSections(seed.sections.map { it.copy(riverId = riverId) })
            }
        }
    }
}

class TripRepository(db: AppDatabase) {
    private val dao = db.tripDao()

    fun observeTrips(): Flow<List<TripEntity>> = dao.observeTrips()
    fun observeTrip(id: Long): Flow<TripEntity?> = dao.observeTrip(id)
    fun observeParticipants(tripId: Long): Flow<List<ParticipantEntity>> =
        dao.observeParticipants(tripId)
    fun observeGear(tripId: Long): Flow<List<GearItemEntity>> = dao.observeGear(tripId)
    fun observeCheckIns(tripId: Long): Flow<List<CheckInEntity>> = dao.observeCheckIns(tripId)

    suspend fun createTrip(
        title: String,
        sectionId: Long?,
        startDateUtcMillis: Long,
        overnight: Boolean,
        organizer: String,
        ownerUsername: String?
    ): Long {
        val tripId = dao.insertTrip(
            TripEntity(
                title = title.trim(),
                sectionId = sectionId,
                startDateUtcMillis = startDateUtcMillis,
                overnight = overnight,
                organizer = organizer.trim(),
                notes = "",
                ownerUsername = ownerUsername
            )
        )
        // Organizator jest pierwszym uczestnikiem.
        if (organizer.isNotBlank()) {
            dao.insertParticipant(
                ParticipantEntity(tripId = tripId, name = organizer.trim(), carSeats = 0, needsKayak = false)
            )
        }
        return tripId
    }

    suspend fun deleteTrip(id: Long) = dao.deleteTrip(id)

    suspend fun addParticipant(tripId: Long, name: String, carSeats: Int, needsKayak: Boolean) {
        dao.insertParticipant(
            ParticipantEntity(
                tripId = tripId,
                name = name.trim(),
                carSeats = carSeats.coerceAtLeast(0),
                needsKayak = needsKayak
            )
        )
    }

    suspend fun updateParticipantData(id: Long, carSeats: Int, needsKayak: Boolean) =
        dao.updateParticipantData(id, carSeats.coerceAtLeast(0), needsKayak)

    suspend fun removeParticipant(id: Long) = dao.deleteParticipant(id)

    suspend fun addGear(tripId: Long, name: String) {
        dao.insertGear(listOf(GearItemEntity(tripId = tripId, name = name.trim())))
    }

    /** Dodaje propozycje wyposażenia, pomijając pozycje o takiej samej nazwie jak istniejące. */
    suspend fun addSuggestedGear(tripId: Long, overnight: Boolean, existingNames: Set<String>) {
        val wanted = SeedData.baseGear + if (overnight) SeedData.overnightGear else emptyList()
        val existing = existingNames.map { it.lowercase() }.toSet()
        val toAdd = wanted
            .filter { it.lowercase() !in existing }
            .map { GearItemEntity(tripId = tripId, name = it) }
        if (toAdd.isNotEmpty()) dao.insertGear(toAdd)
    }

    suspend fun setGearPacked(id: Long, packed: Boolean) = dao.setGearPacked(id, packed)
    suspend fun assignGear(id: Long, assignee: String?) = dao.assignGear(id, assignee)
    suspend fun removeGear(id: Long) = dao.deleteGear(id)

    suspend fun checkIn(
        tripId: Long,
        personName: String,
        lat: Double,
        lon: Double,
        fixAt: Long,
        needsHelp: Boolean
    ) {
        dao.insertCheckIn(
            CheckInEntity(
                tripId = tripId,
                personName = personName.trim(),
                lat = lat,
                lon = lon,
                fixAt = fixAt,
                createdAt = System.currentTimeMillis(),
                needsHelp = needsHelp
            )
        )
    }
}

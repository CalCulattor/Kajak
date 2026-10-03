package pl.kajakapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RiverDao {
    @Query("SELECT COUNT(*) FROM rivers")
    suspend fun countRivers(): Int

    @Query("SELECT * FROM rivers ORDER BY name")
    fun observeRivers(): Flow<List<RiverEntity>>

    @Query("SELECT * FROM sections WHERE riverId = :riverId ORDER BY name")
    fun observeSections(riverId: Long): Flow<List<SectionEntity>>

    @Query(
        "SELECT s.*, r.name AS riverName FROM sections s " +
            "INNER JOIN rivers r ON r.id = s.riverId WHERE s.id = :sectionId"
    )
    fun observeSection(sectionId: Long): Flow<SectionWithRiver?>

    @Query(
        "SELECT s.*, r.name AS riverName FROM sections s " +
            "INNER JOIN rivers r ON r.id = s.riverId ORDER BY r.name, s.name"
    )
    fun observeAllSections(): Flow<List<SectionWithRiver>>

    @Insert
    suspend fun insertRiver(river: RiverEntity): Long

    @Insert
    suspend fun insertSections(sections: List<SectionEntity>)

    @Query("UPDATE sections SET stationName = :stationName WHERE id = :sectionId")
    suspend fun updateStation(sectionId: Long, stationName: String?)

    @Insert
    suspend fun insertSection(section: SectionEntity): Long

    @Query("SELECT * FROM rivers WHERE name = :name AND region = :region LIMIT 1")
    suspend fun findRiver(name: String, region: String): RiverEntity?

    @Query("SELECT * FROM sections WHERE serverKey = :key LIMIT 1")
    suspend fun findSectionByKey(key: String): SectionEntity?

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun getSection(id: Long): SectionEntity?

    @Query("SELECT * FROM rivers WHERE id = :id")
    suspend fun getRiver(id: Long): RiverEntity?

    @Query("SELECT * FROM sections WHERE pendingSync = 1 AND serverKey IS NULL ORDER BY id")
    suspend fun pendingSections(): List<SectionEntity>

    @Query("UPDATE sections SET serverKey = :key, pendingSync = 0 WHERE id = :id")
    suspend fun markSectionSynced(id: Long, key: String)
}

@Dao
interface ObstacleDao {
    @Query("SELECT * FROM obstacles WHERE sectionId = :sectionId ORDER BY reportedAt DESC")
    fun observeForSection(sectionId: Long): Flow<List<ObstacleEntity>>

    @Insert
    suspend fun insert(obstacle: ObstacleEntity): Long

    @Query(
        "UPDATE obstacles SET confirmations = confirmations + 1, " +
            "pendingConfirms = pendingConfirms + 1, lastVerifiedAt = :now, pendingSync = 1 WHERE id = :id"
    )
    suspend fun confirm(id: Long, now: Long)

    @Query(
        "UPDATE obstacles SET removalVotes = removalVotes + 1, " +
            "pendingRemovals = pendingRemovals + 1, lastVerifiedAt = :now, pendingSync = 1 WHERE id = :id"
    )
    suspend fun voteRemoved(id: Long, now: Long)

    @Query("SELECT * FROM obstacles WHERE id = :id")
    suspend fun get(id: Long): ObstacleEntity?

    @Query("SELECT * FROM obstacles WHERE sectionId = :sectionId")
    suspend fun listForSection(sectionId: Long): List<ObstacleEntity>

    @Query("SELECT * FROM obstacles WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: Long): ObstacleEntity?

    @Update
    suspend fun update(obstacle: ObstacleEntity)
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM water_cache WHERE sectionId = :sectionId")
    fun observeWater(sectionId: Long): Flow<WaterCacheEntity?>

    @Query("SELECT * FROM weather_cache WHERE sectionId = :sectionId")
    fun observeWeather(sectionId: Long): Flow<WeatherCacheEntity?>

    @Upsert
    suspend fun upsertWater(entity: WaterCacheEntity)

    @Upsert
    suspend fun upsertWeather(entity: WeatherCacheEntity)

    @Query("DELETE FROM water_cache WHERE sectionId = :sectionId")
    suspend fun clearWater(sectionId: Long)
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startDateUtcMillis")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeTrip(id: Long): Flow<TripEntity?>

    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Query("SELECT * FROM participants WHERE tripId = :tripId ORDER BY id")
    fun observeParticipants(tripId: Long): Flow<List<ParticipantEntity>>

    @Insert
    suspend fun insertParticipant(participant: ParticipantEntity): Long

    @Query("DELETE FROM participants WHERE id = :id")
    suspend fun deleteParticipant(id: Long)

    @Query("SELECT * FROM gear_items WHERE tripId = :tripId ORDER BY id")
    fun observeGear(tripId: Long): Flow<List<GearItemEntity>>

    @Insert
    suspend fun insertGear(items: List<GearItemEntity>)

    @Query("UPDATE gear_items SET packed = :packed WHERE id = :id")
    suspend fun setGearPacked(id: Long, packed: Boolean)

    @Query("UPDATE gear_items SET assignedTo = :assignee WHERE id = :id")
    suspend fun assignGear(id: Long, assignee: String?)

    @Query("DELETE FROM gear_items WHERE id = :id")
    suspend fun deleteGear(id: Long)

    @Query("SELECT * FROM check_ins WHERE tripId = :tripId ORDER BY createdAt DESC")
    fun observeCheckIns(tripId: Long): Flow<List<CheckInEntity>>

    @Insert
    suspend fun insertCheckIn(checkIn: CheckInEntity): Long

    // --- synchronizacja z serwerem

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTrip(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE serverId = :serverId LIMIT 1")
    suspend fun findTripByServerId(serverId: Long): TripEntity?

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("SELECT id FROM trips WHERE serverId IS NOT NULL")
    suspend fun sharedTripIds(): List<Long>

    /** Usuwa lokalne kopie spływów z serwera (np. po zmianie konta); spływy tylko lokalne zostają. */
    @Query("DELETE FROM trips WHERE serverId IS NOT NULL")
    suspend fun deleteSharedTrips()

    @Query("UPDATE participants SET carSeats = :carSeats, needsKayak = :needsKayak WHERE id = :id")
    suspend fun updateParticipantData(id: Long, carSeats: Int, needsKayak: Boolean)

    @Query("SELECT * FROM participants WHERE tripId = :tripId ORDER BY id")
    suspend fun participantsOf(tripId: Long): List<ParticipantEntity>

    @Query("SELECT * FROM participants WHERE id = :id")
    suspend fun getParticipant(id: Long): ParticipantEntity?

    @Update
    suspend fun updateParticipant(participant: ParticipantEntity)

    @Query("SELECT * FROM gear_items WHERE tripId = :tripId ORDER BY id")
    suspend fun gearOf(tripId: Long): List<GearItemEntity>

    @Query("SELECT * FROM gear_items WHERE id = :id")
    suspend fun getGear(id: Long): GearItemEntity?

    @Update
    suspend fun updateGear(gear: GearItemEntity)

    @Insert
    suspend fun insertGearItem(gear: GearItemEntity): Long

    @Query("SELECT * FROM check_ins WHERE tripId = :tripId ORDER BY id")
    suspend fun checkInsOf(tripId: Long): List<CheckInEntity>

    @Update
    suspend fun updateCheckIn(checkIn: CheckInEntity)
}

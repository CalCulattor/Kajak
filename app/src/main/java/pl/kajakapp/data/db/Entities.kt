package pl.kajakapp.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import pl.kajakapp.domain.Difficulty
import pl.kajakapp.domain.ObstacleType
import pl.kajakapp.domain.RiverType
import pl.kajakapp.domain.WaterReading
import pl.kajakapp.domain.WeatherSnapshot

@Entity(tableName = "rivers")
data class RiverEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val region: String,
    val type: RiverType,
    val description: String
)

/** Odcinek rzeki (spływ odcinkowy). Wodowskaz jest opcjonalny i można go zmienić w aplikacji. */
@Entity(
    tableName = "sections",
    foreignKeys = [
        ForeignKey(
            entity = RiverEntity::class,
            parentColumns = ["id"],
            childColumns = ["riverId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("riverId")]
)
data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val riverId: Long,
    val name: String,
    val lengthKm: Double,
    val difficulty: Difficulty,
    val putIn: String,
    val takeOut: String,
    val lat: Double,
    val lon: Double,
    val stationName: String?,
    val description: String
)

/** Wynik zapytania łączącego odcinek z nazwą rzeki. */
data class SectionWithRiver(
    @Embedded val section: SectionEntity,
    @ColumnInfo(name = "riverName") val riverName: String
)

@Entity(
    tableName = "obstacles",
    foreignKeys = [
        ForeignKey(
            entity = SectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sectionId")]
)
data class ObstacleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sectionId: Long,
    val type: ObstacleType,
    val description: String,
    val lat: Double?,
    val lon: Double?,
    val reportedAt: Long,
    val lastVerifiedAt: Long,
    val confirmations: Int = 1,
    val removalVotes: Int = 0,
    /** Hak pod przyszłą synchronizację z serwerem (backend nie jest częścią tego projektu). */
    val pendingSync: Boolean = true
)

@Entity(tableName = "water_cache")
data class WaterCacheEntity(
    @PrimaryKey val sectionId: Long,
    val stationName: String,
    val levelCm: Int?,
    val levelMeasuredAt: String?,
    val measuredAtMillis: Long?,
    val flowM3s: Double?,
    val waterTempC: Double?,
    val iceActive: Boolean,
    val warningCm: Int?,
    val alarmCm: Int?,
    val fetchedAt: Long
) {
    fun toDomain() = WaterReading(
        stationName = stationName,
        levelCm = levelCm,
        levelMeasuredAt = levelMeasuredAt,
        measuredAtMillis = measuredAtMillis,
        flowM3s = flowM3s,
        waterTempC = waterTempC,
        iceActive = iceActive,
        warningCm = warningCm,
        alarmCm = alarmCm,
        fetchedAt = fetchedAt
    )
}

@Entity(tableName = "weather_cache")
data class WeatherCacheEntity(
    @PrimaryKey val sectionId: Long,
    val airTempC: Double?,
    val windGustMs: Double?,
    val precipitationMm: Double?,
    val thunderstorm: Boolean,
    val fetchedAt: Long
) {
    fun toDomain() = WeatherSnapshot(
        airTempC = airTempC,
        windGustMs = windGustMs,
        precipitationMm = precipitationMm,
        thunderstorm = thunderstorm,
        fetchedAt = fetchedAt
    )
}

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sectionId: Long?,
    /** Północ UTC wybranego dnia (tak zwraca DatePicker). */
    val startDateUtcMillis: Long,
    val overnight: Boolean,
    val organizer: String,
    val notes: String
)

@Entity(
    tableName = "participants",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tripId")]
)
data class ParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    /** Liczba miejsc w aucie tej osoby, wraz z kierowcą (0 = nie jedzie autem). */
    val carSeats: Int,
    val needsKayak: Boolean
)

@Entity(
    tableName = "gear_items",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tripId")]
)
data class GearItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val assignedTo: String? = null,
    val packed: Boolean = false
)

@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tripId")]
)
data class CheckInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val personName: String,
    val lat: Double,
    val lon: Double,
    /** Kiedy system zarejestrował pozycję GPS (może być starsza niż [createdAt]). */
    val fixAt: Long,
    val createdAt: Long,
    val needsHelp: Boolean,
    /** Hak pod przyszłą synchronizację z serwerem (backend nie jest częścią tego projektu). */
    val pendingSync: Boolean = true
)

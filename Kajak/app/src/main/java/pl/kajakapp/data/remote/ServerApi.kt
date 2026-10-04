package pl.kajakapp.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/*
 * Klient serwera KajakApp (server/ w tym repozytorium). Nazwy pól JSON muszą dokładnie
 * odpowiadać serwerowi, bo ten odrzuca żądania z nieznanymi polami. Pola żądań z wartością
 * domyślną (null) są pomijane w JSON-ie, pola bez wartości domyślnej są wysyłane zawsze.
 */

@Serializable
data class HealthDto(val status: String? = null)

// ---------------------------------------------------------------- trasy

@Serializable
data class RouteDto(
    val key: String,
    @SerialName("river_name") val riverName: String,
    val region: String = "",
    @SerialName("river_type") val riverType: String = "LOWLAND",
    val name: String,
    @SerialName("length_km") val lengthKm: Double = 0.0,
    val difficulty: String = "FLAT",
    @SerialName("put_in") val putIn: String = "",
    @SerialName("take_out") val takeOut: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @SerialName("station_name") val stationName: String? = null,
    val description: String = ""
)

@Serializable
data class RouteRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("river_name") val riverName: String,
    val region: String,
    @SerialName("river_type") val riverType: String,
    val name: String,
    @SerialName("length_km") val lengthKm: Double,
    val difficulty: String,
    @SerialName("put_in") val putIn: String,
    @SerialName("take_out") val takeOut: String,
    val lat: Double,
    val lon: Double,
    @SerialName("station_name") val stationName: String,
    val description: String
)

// ---------------------------------------------------------------- przeszkody

@Serializable
data class ObstacleDto(
    val id: Long,
    @SerialName("client_id") val clientId: String? = null,
    val type: String,
    val description: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("reported_at") val reportedAt: String = "",
    @SerialName("last_verified_at") val lastVerifiedAt: String = "",
    val confirmations: Int = 1,
    @SerialName("removal_votes") val removalVotes: Int = 0
)

@Serializable
data class ObstacleRequest(
    @SerialName("client_id") val clientId: String,
    val type: String,
    val description: String,
    val lat: Double? = null,
    val lon: Double? = null
)

// ---------------------------------------------------------------- spływy

@Serializable
data class TripDto(
    val id: Long,
    val title: String,
    @SerialName("section_key") val sectionKey: String? = null,
    @SerialName("start_date") val startDate: String,
    @SerialName("start_time") val startTime: String = "",
    val overnight: Boolean = false,
    val organizer: String = "",
    val notes: String = ""
)

@Serializable
data class TripRequest(
    val title: String,
    @SerialName("section_key") val sectionKey: String? = null,
    @SerialName("start_date") val startDate: String,
    @SerialName("start_time") val startTime: String = "",
    val overnight: Boolean,
    val notes: String
)

@Serializable
data class ParticipantDto(
    val id: Long,
    val name: String,
    @SerialName("car_seats") val carSeats: Int = 0,
    @SerialName("needs_kayak") val needsKayak: Boolean = false,
    @SerialName("is_organizer") val isOrganizer: Boolean = false
)

/** Uczestnika można dodać tylko jako siebie – nazwę serwer bierze z konta, więc jej nie wysyłamy. */
@Serializable
data class ParticipantRequest(
    @SerialName("car_seats") val carSeats: Int,
    @SerialName("needs_kayak") val needsKayak: Boolean
)

@Serializable
data class GearDto(
    val id: Long,
    val name: String,
    @SerialName("assigned_to") val assignedTo: String? = null,
    val packed: Boolean = false,
    /** "required" albo "recommended"; starsze serwery tego pola nie wysyłają. */
    val requirement: String = "recommended",
    /** Uczestnicy, którzy potwierdzili, że mają ten element. */
    @SerialName("confirmed_by") val confirmedBy: List<String> = emptyList()
)

@Serializable
data class GearRequest(
    val name: String,
    @SerialName("assigned_to") val assignedTo: String? = null,
    val requirement: String = "recommended"
)

/** Zmiana wymagalności pozycji (tylko organizator). Pole bez wartości domyślnej, więc zawsze trafia do JSON-a. */
@Serializable
data class GearRequirementRequest(val requirement: String)

/** Potwierdzenie (albo cofnięcie potwierdzenia) przez zalogowanego uczestnika – zawsze za siebie. */
@Serializable
data class GearConfirmRequest(val confirmed: Boolean)

/** Oba pola bez wartości domyślnych, więc zawsze trafiają do JSON-a (także `assigned_to: null`). */
@Serializable
data class GearPatchRequest(
    @SerialName("assigned_to") val assignedTo: String?,
    val packed: Boolean
)

@Serializable
data class CheckInDto(
    val id: Long,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("person_name") val personName: String,
    val lat: Double,
    val lon: Double,
    @SerialName("fix_at") val fixAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("needs_help") val needsHelp: Boolean = false
)

@Serializable
data class CheckInRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("person_name") val personName: String,
    val lat: Double,
    val lon: Double,
    @SerialName("fix_at") val fixAt: String,
    @SerialName("needs_help") val needsHelp: Boolean
)

@Serializable
data class LocationRequest(
    val lat: Double,
    val lon: Double,
    @SerialName("fix_at") val fixAt: String
)

@Serializable
data class LocationDto(
    val username: String,
    val lat: Double,
    val lon: Double,
    @SerialName("fix_at") val fixAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("needs_help") val needsHelp: Boolean = false,
    @SerialName("help_check_in_id") val helpCheckInId: Long = 0
)

@Serializable
data class SosDto(
    @SerialName("check_in_id") val checkInId: Long,
    @SerialName("person_name") val personName: String,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @SerialName("river_key") val riverKey: String = "",
    val member: Boolean = false,
    @SerialName("trip_id") val tripId: Long = 0,
    @SerialName("trip_title") val tripTitle: String = ""
)

@Serializable
data class CheckInHelpRequest(@SerialName("needs_help") val needsHelp: Boolean)

@Serializable
data class TripDetailDto(
    val trip: TripDto,
    val participants: List<ParticipantDto> = emptyList(),
    val gear: List<GearDto> = emptyList(),
    @SerialName("check_ins") val checkIns: List<CheckInDto> = emptyList()
)

interface KajakServerApi {
    @GET("api/health")
    suspend fun health(): HealthDto

    @POST("api/register")
    suspend fun register(@Body body: AuthRequest): AuthResponse

    @POST("api/login")
    suspend fun login(@Body body: AuthRequest): AuthResponse

    @POST("api/logout")
    suspend fun logout(): Response<Unit>

    @GET("api/routes")
    suspend fun routes(): List<RouteDto>

    @POST("api/routes")
    suspend fun createRoute(@Body body: RouteRequest): RouteDto

    @GET("api/sections/{key}/obstacles")
    suspend fun obstacles(
        @Path("key") key: String,
        @Query("include_inactive") includeInactive: Boolean = true
    ): List<ObstacleDto>

    @POST("api/sections/{key}/obstacles")
    suspend fun createObstacle(@Path("key") key: String, @Body body: ObstacleRequest): ObstacleDto

    @POST("api/obstacles/{id}/confirm")
    suspend fun confirmObstacle(@Path("id") id: Long): ObstacleDto

    @POST("api/obstacles/{id}/remove-vote")
    suspend fun voteObstacleRemoved(@Path("id") id: Long): ObstacleDto

    @GET("api/trips")
    suspend fun trips(): List<TripDto>

    @POST("api/trips")
    suspend fun createTrip(@Body body: TripRequest): TripDto

    @GET("api/trips/{id}")
    suspend fun trip(@Path("id") id: Long): TripDetailDto

    @DELETE("api/trips/{id}")
    suspend fun deleteTrip(@Path("id") id: Long): Response<Unit>

    @POST("api/trips/{id}/participants")
    suspend fun addParticipant(@Path("id") tripId: Long, @Body body: ParticipantRequest): ParticipantDto

    @POST("api/trips/{id}/participants/{pid}/organizer")
    suspend fun promoteParticipant(@Path("id") tripId: Long, @Path("pid") id: Long): ParticipantDto

    @DELETE("api/trips/{id}/participants/{pid}")
    suspend fun deleteParticipant(@Path("id") tripId: Long, @Path("pid") id: Long): Response<Unit>

    @POST("api/trips/{id}/gear")
    suspend fun addGear(@Path("id") tripId: Long, @Body body: GearRequest): GearDto

    @PATCH("api/trips/{id}/gear/{gid}")
    suspend fun patchGear(
        @Path("id") tripId: Long,
        @Path("gid") id: Long,
        @Body body: GearPatchRequest
    ): GearDto

    @PATCH("api/trips/{id}/gear/{gid}")
    suspend fun patchGearRequirement(
        @Path("id") tripId: Long,
        @Path("gid") id: Long,
        @Body body: GearRequirementRequest
    ): GearDto

    @PUT("api/trips/{id}/gear/{gid}/confirm")
    suspend fun confirmGear(
        @Path("id") tripId: Long,
        @Path("gid") id: Long,
        @Body body: GearConfirmRequest
    ): GearDto

    @DELETE("api/trips/{id}/gear/{gid}")
    suspend fun deleteGear(@Path("id") tripId: Long, @Path("gid") id: Long): Response<Unit>

    @POST("api/trips/{id}/checkins")
    suspend fun addCheckIn(@Path("id") tripId: Long, @Body body: CheckInRequest): CheckInDto

    @PUT("api/trips/{id}/location")
    suspend fun putLocation(@Path("id") tripId: Long, @Body body: LocationRequest): Response<Unit>

    @DELETE("api/trips/{id}/location")
    suspend fun deleteLocation(@Path("id") tripId: Long): Response<Unit>

    @GET("api/sos")
    suspend fun sos(): List<SosDto>

    @GET("api/trips/{id}/locations")
    suspend fun locations(@Path("id") tripId: Long): List<LocationDto>

    @PATCH("api/trips/{id}/checkins/{cid}")
    suspend fun patchCheckIn(
        @Path("id") tripId: Long,
        @Path("cid") id: Long,
        @Body body: CheckInHelpRequest
    ): CheckInDto
}

// ---------------------------------------------------------------- konta

@Serializable
data class AuthRequest(val username: String, val password: String)

@Serializable
data class AuthResponse(val token: String, val username: String)

package pl.kajakapp.data

import androidx.room.withTransaction
import java.io.IOException
import java.net.UnknownServiceException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import pl.kajakapp.data.db.AppDatabase
import pl.kajakapp.data.db.CheckInEntity
import pl.kajakapp.data.db.GearItemEntity
import pl.kajakapp.data.db.ObstacleEntity
import pl.kajakapp.data.db.ParticipantEntity
import pl.kajakapp.data.db.RiverEntity
import pl.kajakapp.data.db.SectionEntity
import pl.kajakapp.data.db.TripEntity
import pl.kajakapp.data.remote.CheckInRequest
import pl.kajakapp.data.remote.GearPatchRequest
import pl.kajakapp.data.remote.GearRequest
import pl.kajakapp.data.remote.KajakServerApi
import pl.kajakapp.data.remote.Network
import pl.kajakapp.data.remote.ObstacleRequest
import pl.kajakapp.data.remote.ParticipantRequest
import pl.kajakapp.data.remote.RouteRequest
import pl.kajakapp.data.remote.TripDetailDto
import pl.kajakapp.data.remote.TripRequest
import pl.kajakapp.domain.Difficulty
import pl.kajakapp.domain.ObstacleType
import pl.kajakapp.domain.RiverType
import retrofit2.HttpException

/**
 * Wynik operacji synchronizacji. [message] jest pusty, gdy nie ma nic do pokazania
 * (np. synchronizacja jest wyłączona albo wszystko poszło dobrze po cichu).
 */
data class SyncOutcome(val ok: Boolean, val message: String)

data class RemoteTripInfo(
    val id: Long,
    val title: String,
    val startDate: String,
    val organizer: String,
    val alreadyJoined: Boolean
)

data class RemoteTripsResult(val outcome: SyncOutcome, val trips: List<RemoteTripInfo>)

data class JoinResult(val outcome: SyncOutcome, val tripId: Long?)

/**
 * Synchronizacja lokalnej bazy z serwerem. Aplikacja działa najpierw lokalnie; wszystko,
 * co nie dotarło na serwer, zostaje oznaczone i jest wysyłane przy następnej synchronizacji.
 * Operacje są wykonywane pojedynczo (mutex), żeby dwie równoległe synchronizacje nie
 * wysłały tych samych danych dwa razy.
 */
class SyncRepository(
    private val db: AppDatabase,
    private val settings: ServerSettings
) {
    private val riverDao = db.riverDao()
    private val obstacleDao = db.obstacleDao()
    private val tripDao = db.tripDao()
    private val mutex = Mutex()

    val enabled: Boolean get() = settings.url.value.isNotEmpty()

    private fun api(): KajakServerApi = Network.server(settings.url.value)

    private val disabled = SyncOutcome(true, "")

    // ------------------------------------------------------------ połączenie

    suspend fun testConnection(): SyncOutcome {
        if (!enabled) return SyncOutcome(false, "Adres serwera jest pusty – synchronizacja wyłączona.")
        return guarded {
            val health = api().health()
            if (health.status == "ok") {
                SyncOutcome(true, "Połączono z serwerem.")
            } else {
                SyncOutcome(false, "Serwer odpowiedział, ale nie wygląda na serwer KajakApp.")
            }
        }
    }

    // ------------------------------------------------------------ trasy

    /** Wysyła trasy dodane w aplikacji, a potem pobiera trasy dodane przez innych. */
    suspend fun syncRoutes(): SyncOutcome {
        if (!enabled) return disabled
        return mutex.withLock {
            guarded {
                val api = api()
                val pushed = pushPendingSections(api)
                var added = 0
                db.withTransaction {
                    for (r in api.routes()) {
                        if (riverDao.findSectionByKey(r.key) != null) continue
                        val riverId = riverDao.findRiver(r.riverName, r.region)?.id
                            ?: riverDao.insertRiver(
                                RiverEntity(
                                    name = r.riverName,
                                    region = r.region,
                                    type = parseEnum(r.riverType, RiverType.LOWLAND),
                                    description = ""
                                )
                            )
                        riverDao.insertSection(
                            SectionEntity(
                                riverId = riverId,
                                name = r.name,
                                lengthKm = r.lengthKm,
                                difficulty = parseEnum(r.difficulty, Difficulty.FLAT),
                                putIn = r.putIn,
                                takeOut = r.takeOut,
                                lat = r.lat,
                                lon = r.lon,
                                stationName = r.stationName?.takeIf { it.isNotBlank() },
                                description = r.description,
                                serverKey = r.key,
                                pendingSync = false
                            )
                        )
                        added++
                    }
                }
                SyncOutcome(true, "Trasy: wysłano $pushed, pobrano $added.")
            }
        }
    }

    /** Wysyła lokalnie dodane odcinki; zwraca liczbę wysłanych. Wołać tylko pod [mutex]. */
    private suspend fun pushPendingSections(api: KajakServerApi): Int {
        var count = 0
        for (section in riverDao.pendingSections()) {
            val river = riverDao.getRiver(section.riverId) ?: continue
            val dto = api.createRoute(
                RouteRequest(
                    clientId = settings.clientId("s", section.id),
                    riverName = river.name,
                    region = river.region,
                    riverType = river.type.name,
                    name = section.name,
                    lengthKm = section.lengthKm,
                    difficulty = section.difficulty.name,
                    putIn = section.putIn,
                    takeOut = section.takeOut,
                    lat = section.lat,
                    lon = section.lon,
                    stationName = section.stationName.orEmpty(),
                    description = section.description
                )
            )
            riverDao.markSectionSynced(section.id, dto.key)
            count++
        }
        return count
    }

    // ------------------------------------------------------------ przeszkody

    /** Wysyła zgłoszenia i głosy przeszkód odcinka, a potem pobiera aktualny stan z serwera. */
    suspend fun syncObstacles(sectionId: Long): SyncOutcome {
        if (!enabled) return disabled
        return mutex.withLock {
            guarded {
                val api = api()
                var section = riverDao.getSection(sectionId)
                    ?: return@guarded SyncOutcome(false, "Nie znaleziono odcinka.")
                if (section.serverKey == null && section.pendingSync) {
                    pushPendingSections(api)
                    section = riverDao.getSection(sectionId)
                        ?: return@guarded SyncOutcome(false, "Nie znaleziono odcinka.")
                }
                val key = section.serverKey
                    ?: return@guarded SyncOutcome(false, "Odcinek nie jest jeszcze na serwerze.")

                pushObstacles(api, key, sectionId)
                pullObstacles(api, key, sectionId)
                SyncOutcome(true, "")
            }
        }
    }

    private suspend fun pushObstacles(api: KajakServerApi, sectionKey: String, sectionId: Long) {
        for (stored in obstacleDao.listForSection(sectionId)) {
            var cur = stored
            if (cur.serverId == null) {
                val dto = api.createObstacle(
                    sectionKey,
                    ObstacleRequest(
                        clientId = settings.clientId("o", cur.id),
                        type = cur.type.name,
                        description = cur.description,
                        lat = cur.lat,
                        lon = cur.lon
                    )
                )
                cur = cur.copy(serverId = dto.id)
                obstacleDao.update(cur)
            }
            val serverId = cur.serverId ?: continue
            while (cur.pendingConfirms > 0) {
                api.confirmObstacle(serverId)
                cur = cur.copy(pendingConfirms = cur.pendingConfirms - 1)
                obstacleDao.update(cur)
            }
            while (cur.pendingRemovals > 0) {
                api.voteObstacleRemoved(serverId)
                cur = cur.copy(pendingRemovals = cur.pendingRemovals - 1)
                obstacleDao.update(cur)
            }
            obstacleDao.update(cur.copy(pendingSync = false))
        }
    }

    private suspend fun pullObstacles(api: KajakServerApi, sectionKey: String, sectionId: Long) {
        val remote = api.obstacles(sectionKey, includeInactive = true)
        val prefix = settings.deviceId + "-o"
        db.withTransaction {
            val locals = obstacleDao.listForSection(sectionId)
            for (r in remote) {
                val ownLocalId = r.clientId
                    ?.takeIf { it.startsWith(prefix) }
                    ?.removePrefix(prefix)
                    ?.toLongOrNull()
                val local = locals.firstOrNull { it.serverId == r.id }
                    ?: locals.firstOrNull { it.serverId == null && it.id == ownLocalId }
                if (local != null) {
                    // Liczniki z serwera nadpisują lokalne tylko wtedy, gdy nie mamy własnych,
                    // jeszcze niewysłanych głosów (inaczej zgubilibyśmy je z widoku).
                    val hasPending = local.pendingConfirms > 0 || local.pendingRemovals > 0
                    obstacleDao.update(
                        if (hasPending) {
                            local.copy(serverId = r.id)
                        } else {
                            local.copy(
                                serverId = r.id,
                                confirmations = r.confirmations,
                                removalVotes = r.removalVotes,
                                lastVerifiedAt = parseTime(r.lastVerifiedAt, local.lastVerifiedAt),
                                pendingSync = false
                            )
                        }
                    )
                } else {
                    val reported = parseTime(r.reportedAt, System.currentTimeMillis())
                    obstacleDao.insert(
                        ObstacleEntity(
                            sectionId = sectionId,
                            type = parseEnum(r.type, ObstacleType.OTHER),
                            description = r.description,
                            lat = r.lat,
                            lon = r.lon,
                            reportedAt = reported,
                            lastVerifiedAt = parseTime(r.lastVerifiedAt, reported),
                            confirmations = r.confirmations,
                            removalVotes = r.removalVotes,
                            pendingSync = false,
                            serverId = r.id
                        )
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ spływy

    /**
     * Pierwsze wywołanie udostępnia spływ na serwerze (z uczestnikami, wyposażeniem i
     * zameldowaniami), kolejne wysyłają nowe elementy i pobierają zmiany innych osób.
     */
    suspend fun syncTrip(tripId: Long): SyncOutcome {
        if (!enabled) return SyncOutcome(false, "Adres serwera jest pusty – synchronizacja wyłączona.")
        return mutex.withLock {
            guarded {
                val api = api()
                var trip = tripDao.getTrip(tripId)
                    ?: return@guarded SyncOutcome(false, "Nie znaleziono spływu.")

                if (trip.serverId == null) {
                    val sectionKey = sectionKeyFor(api, trip.sectionId)
                    val created = api.createTrip(
                        TripRequest(
                            title = trip.title,
                            sectionKey = sectionKey,
                            startDate = toIsoDate(trip.startDateUtcMillis),
                            overnight = trip.overnight,
                            organizer = trip.organizer,
                            notes = trip.notes
                        )
                    )
                    trip = trip.copy(serverId = created.id)
                    tripDao.updateTrip(trip)
                }
                val serverTripId = trip.serverId
                    ?: return@guarded SyncOutcome(false, "Spływ nie ma identyfikatora na serwerze.")

                pushTripChildren(api, tripId, serverTripId)
                mergeTrip(tripId, api.trip(serverTripId))
                SyncOutcome(true, "Spływ jest zsynchronizowany z serwerem.")
            }
        }
    }

    private suspend fun sectionKeyFor(api: KajakServerApi, sectionId: Long?): String? {
        if (sectionId == null) return null
        var section = riverDao.getSection(sectionId) ?: return null
        if (section.serverKey == null && section.pendingSync) {
            pushPendingSections(api)
            section = riverDao.getSection(sectionId) ?: return null
        }
        return section.serverKey
    }

    private suspend fun pushTripChildren(api: KajakServerApi, tripId: Long, serverTripId: Long) {
        // Stan serwera przed wysyłką: pozwala dopasować istniejące elementy po nazwie zamiast
        // dublować je (organizator jest tworzony przez serwer razem ze spływem).
        val remote = api.trip(serverTripId)
        val linkedParticipants = tripDao.participantsOf(tripId).mapNotNull { it.serverId }.toMutableSet()
        val linkedGear = tripDao.gearOf(tripId).mapNotNull { it.serverId }.toMutableSet()

        for (p in tripDao.participantsOf(tripId)) {
            if (p.serverId != null) continue
            val match = remote.participants.firstOrNull {
                it.id !in linkedParticipants && it.name.equals(p.name, ignoreCase = true)
            }
            val remoteId = if (match != null) {
                match.id
            } else {
                api.addParticipant(
                    serverTripId,
                    ParticipantRequest(p.name, p.carSeats, p.needsKayak)
                ).id
            }
            linkedParticipants += remoteId
            tripDao.updateParticipant(p.copy(serverId = remoteId))
        }

        for (g in tripDao.gearOf(tripId)) {
            if (g.serverId != null) continue
            val match = remote.gear.firstOrNull {
                it.id !in linkedGear && it.name.equals(g.name, ignoreCase = true)
            }
            val remoteId = if (match != null) {
                match.id
            } else {
                val created = api.addGear(serverTripId, GearRequest(g.name, g.assignedTo))
                if (g.packed) api.patchGear(serverTripId, created.id, GearPatchRequest(g.assignedTo, true))
                created.id
            }
            linkedGear += remoteId
            tripDao.updateGear(g.copy(serverId = remoteId))
        }

        for (c in tripDao.checkInsOf(tripId)) {
            if (c.serverId != null) continue
            val dto = api.addCheckIn(
                serverTripId,
                CheckInRequest(
                    clientId = settings.clientId("c", c.id),
                    personName = c.personName,
                    lat = c.lat,
                    lon = c.lon,
                    fixAt = Instant.ofEpochMilli(c.fixAt).toString(),
                    needsHelp = c.needsHelp
                )
            )
            tripDao.updateCheckIn(c.copy(serverId = dto.id, pendingSync = false))
        }
    }

    /** Dopasowuje lokalny spływ do stanu z serwera (serwer wygrywa przy różnicach). */
    private suspend fun mergeTrip(tripId: Long, detail: TripDetailDto) {
        db.withTransaction {
            val trip = tripDao.getTrip(tripId) ?: return@withTransaction
            val sectionId = detail.trip.sectionKey
                ?.let { riverDao.findSectionByKey(it)?.id }
                ?: trip.sectionId
            tripDao.updateTrip(
                trip.copy(
                    title = detail.trip.title,
                    sectionId = sectionId,
                    startDateUtcMillis = fromIsoDate(detail.trip.startDate, trip.startDateUtcMillis),
                    overnight = detail.trip.overnight,
                    organizer = detail.trip.organizer.ifBlank { trip.organizer },
                    notes = detail.trip.notes
                )
            )

            // Uczestnicy
            val remoteParticipantIds = detail.participants.map { it.id }.toSet()
            val localParticipants = tripDao.participantsOf(tripId)
            for (local in localParticipants) {
                val sid = local.serverId ?: continue
                if (sid !in remoteParticipantIds) tripDao.deleteParticipant(local.id)
            }
            for (r in detail.participants) {
                val local = localParticipants.firstOrNull { it.serverId == r.id }
                if (local != null) {
                    tripDao.updateParticipant(
                        local.copy(name = r.name, carSeats = r.carSeats, needsKayak = r.needsKayak)
                    )
                } else {
                    tripDao.insertParticipant(
                        ParticipantEntity(
                            tripId = tripId,
                            name = r.name,
                            carSeats = r.carSeats,
                            needsKayak = r.needsKayak,
                            serverId = r.id
                        )
                    )
                }
            }

            // Wyposażenie
            val remoteGearIds = detail.gear.map { it.id }.toSet()
            val localGear = tripDao.gearOf(tripId)
            for (local in localGear) {
                val sid = local.serverId ?: continue
                if (sid !in remoteGearIds) tripDao.deleteGear(local.id)
            }
            for (r in detail.gear) {
                val local = localGear.firstOrNull { it.serverId == r.id }
                if (local != null) {
                    tripDao.updateGear(local.copy(name = r.name, assignedTo = r.assignedTo, packed = r.packed))
                } else {
                    tripDao.insertGearItem(
                        GearItemEntity(
                            tripId = tripId,
                            name = r.name,
                            assignedTo = r.assignedTo,
                            packed = r.packed,
                            serverId = r.id
                        )
                    )
                }
            }

            // Zameldowania (tylko dopisujemy; lokalnie niczego nie usuwamy)
            val localCheckIns = tripDao.checkInsOf(tripId)
            for (r in detail.checkIns) {
                if (localCheckIns.any { it.serverId == r.id }) continue
                val fix = parseTime(r.fixAt, System.currentTimeMillis())
                tripDao.insertCheckIn(
                    CheckInEntity(
                        tripId = tripId,
                        personName = r.personName,
                        lat = r.lat,
                        lon = r.lon,
                        fixAt = fix,
                        createdAt = parseTime(r.createdAt, fix),
                        needsHelp = r.needsHelp,
                        pendingSync = false,
                        serverId = r.id
                    )
                )
            }
        }
    }

    /** Lista spływów dostępnych na serwerze (do dołączenia). */
    suspend fun listRemoteTrips(): RemoteTripsResult {
        if (!enabled) {
            return RemoteTripsResult(SyncOutcome(false, "Adres serwera jest pusty – synchronizacja wyłączona."), emptyList())
        }
        var trips: List<RemoteTripInfo> = emptyList()
        val outcome = guarded {
            trips = api().trips().map {
                RemoteTripInfo(
                    id = it.id,
                    title = it.title,
                    startDate = it.startDate,
                    organizer = it.organizer,
                    alreadyJoined = tripDao.findTripByServerId(it.id) != null
                )
            }.sortedByDescending { it.startDate }
            SyncOutcome(true, "")
        }
        return RemoteTripsResult(outcome, trips)
    }

    /** Dodaje do lokalnej bazy spływ z serwera (jeśli go jeszcze nie ma) i zwraca jego lokalne id. */
    suspend fun joinTrip(remoteTripId: Long): JoinResult {
        if (!enabled) {
            return JoinResult(SyncOutcome(false, "Adres serwera jest pusty – synchronizacja wyłączona."), null)
        }
        var localId: Long? = null
        val outcome = mutex.withLock {
            guarded {
                tripDao.findTripByServerId(remoteTripId)?.let {
                    localId = it.id
                    return@guarded SyncOutcome(true, "Ten spływ już masz na liście.")
                }
                val detail = api().trip(remoteTripId)
                val sectionId = detail.trip.sectionKey?.let { riverDao.findSectionByKey(it)?.id }
                val id = tripDao.insertTrip(
                    TripEntity(
                        title = detail.trip.title,
                        sectionId = sectionId,
                        startDateUtcMillis = fromIsoDate(detail.trip.startDate, System.currentTimeMillis()),
                        overnight = detail.trip.overnight,
                        organizer = detail.trip.organizer,
                        notes = detail.trip.notes,
                        serverId = remoteTripId
                    )
                )
                mergeTrip(id, detail)
                localId = id
                SyncOutcome(true, "Dołączono do spływu.")
            }
        }
        return JoinResult(outcome, localId)
    }

    // ------------------------------------------------------------ pojedyncze zmiany (best effort)

    /** Wysyła aktualny stan jednej pozycji wyposażenia, jeśli spływ jest udostępniony. */
    suspend fun pushGear(gearId: Long): SyncOutcome {
        if (!enabled) return disabled
        return mutex.withLock {
            guarded {
                val gear = tripDao.getGear(gearId) ?: return@guarded disabled
                val serverGearId = gear.serverId ?: return@guarded disabled
                val serverTripId = tripDao.getTrip(gear.tripId)?.serverId ?: return@guarded disabled
                api().patchGear(serverTripId, serverGearId, GearPatchRequest(gear.assignedTo, gear.packed))
                SyncOutcome(true, "")
            }
        }
    }

    /** Usuwa na serwerze uczestnika lub wyposażenie, które właśnie usunięto lokalnie. */
    suspend fun deleteRemote(serverTripId: Long?, participantServerId: Long?, gearServerId: Long?): SyncOutcome {
        if (!enabled || serverTripId == null) return disabled
        return mutex.withLock {
            guarded {
                val api = api()
                participantServerId?.let { ignoreNotFound(api.deleteParticipant(serverTripId, it)) }
                gearServerId?.let { ignoreNotFound(api.deleteGear(serverTripId, it)) }
                SyncOutcome(true, "")
            }
        }
    }

    private fun ignoreNotFound(response: retrofit2.Response<Unit>) {
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
    }

    // ------------------------------------------------------------ pomocnicze

    private suspend fun guarded(block: suspend () -> SyncOutcome): SyncOutcome =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            SyncOutcome(false, describeHttp(e))
        } catch (e: UnknownServiceException) {
            SyncOutcome(false, "Połączenie bez szyfrowania (http) jest zablokowane – użyj adresu https.")
        } catch (e: IOException) {
            SyncOutcome(false, "Brak połączenia z serwerem. Dane zapisane lokalnie wyślę później.")
        } catch (e: SerializationException) {
            SyncOutcome(false, "Nieoczekiwana odpowiedź serwera.")
        } catch (e: IllegalArgumentException) {
            SyncOutcome(false, "Niepoprawny adres serwera.")
        } catch (e: Exception) {
            // Ostatnia deska ratunku (np. błąd bazy w trakcie scalania), żeby synchronizacja nie zawieszała aplikacji.
            SyncOutcome(false, "Błąd synchronizacji: ${e.javaClass.simpleName}.")
        }

    private fun describeHttp(e: HttpException): String {
        val detail = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            ?.let { ERROR_FIELD.find(it)?.groupValues?.get(1) }
        return when (e.code()) {
            404 -> "Serwer nie obsługuje tej funkcji lub nie ma takiego zasobu (może wymaga aktualizacji serwera)."
            400, 413 -> "Serwer odrzucił dane" + (detail?.let { ": $it" } ?: ".")
            else -> "Błąd serwera (HTTP ${e.code()})."
        }
    }

    private fun toIsoDate(utcMillis: Long): String =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun fromIsoDate(text: String, fallback: Long): Long =
        runCatching {
            LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrDefault(fallback)

    private fun parseTime(text: String, fallback: Long): Long =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(fallback)

    private inline fun <reified T : Enum<T>> parseEnum(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        val ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"([^\"]*)\"")
    }
}

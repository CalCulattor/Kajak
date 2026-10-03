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
import pl.kajakapp.data.remote.AuthRequest
import pl.kajakapp.data.remote.CheckInHelpRequest
import pl.kajakapp.data.remote.LocationRequest
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

/** Uczestnik spływu widoczny na mapie. [helpCheckInId] to zameldowanie z prośbą o pomoc (0 = brak prośby). */
data class PersonOnMap(
    val name: String,
    val lat: Double,
    val lon: Double,
    val needsHelp: Boolean,
    val helpCheckInId: Long,
    val updatedAt: Long
)

/** Wynik pobrania pozycji: lista albo opis problemu ([error] != null). */
data class LocationsResult(val people: List<PersonOnMap> = emptyList(), val error: String? = null)

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

    private fun api(): KajakServerApi =
        Network.server(settings.url.value) { settings.session.value?.token }

    private val username: String? get() = settings.session.value?.username
    private val loggedIn: Boolean get() = settings.session.value != null

    private val notEnabled = SyncOutcome(false, "Adres serwera jest pusty – synchronizacja wyłączona.")

    private fun needLogin(what: String) =
        SyncOutcome(false, "Zaloguj się (ikona ustawień), aby $what.")

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

    // ------------------------------------------------------------ konto

    /** Rejestruje nowe konto ([register] = true) albo loguje się na istniejące. */
    suspend fun authenticate(name: String, password: String, register: Boolean): SyncOutcome {
        if (!enabled) return notEnabled
        return guarded {
            try {
                val body = AuthRequest(name.trim(), password)
                val response = if (register) api().register(body) else api().login(body)
                val previous = settings.lastAccount
                val mirror = settings.mirrorUrl
                val sameServer = mirror == null || mirror == settings.url.value
                settings.setSession(response.username, response.token)
                val sameAccount = previous == null || previous.equals(response.username, ignoreCase = true)
                if (!sameAccount || !sameServer) {
                    // Lokalne kopie spływów innego konta lub innego serwera nie pasują do tej sesji.
                    tripDao.deleteSharedTrips()
                }
                SyncOutcome(
                    true,
                    if (register) {
                        "Konto utworzone. Zalogowano jako ${response.username}."
                    } else {
                        "Zalogowano jako ${response.username}."
                    }
                )
            } catch (e: HttpException) {
                SyncOutcome(false, authMessage(e))
            }
        }
    }

    /** Wylogowuje (unieważnia sesję na serwerze, o ile się da) i zawsze czyści sesję lokalnie. */
    suspend fun logout(): SyncOutcome {
        if (enabled && loggedIn) {
            guarded {
                api().logout()
                SyncOutcome(true, "")
            }
        }
        settings.clearSession()
        return SyncOutcome(true, "Wylogowano.")
    }

    private fun authMessage(e: HttpException): String = when (e.code()) {
        401 -> "Niepoprawna nazwa użytkownika lub hasło."
        409 -> "Ta nazwa użytkownika jest już zajęta."
        429 -> "Zbyt wiele prób. Spróbuj ponownie za kilka minut."
        else -> describeHttp(e)
    }

    // ------------------------------------------------------------ trasy

    /** Wysyła trasy dodane w aplikacji, a potem pobiera trasy dodane przez innych. */
    suspend fun syncRoutes(): SyncOutcome {
        if (!enabled) return disabled
        return mutex.withLock {
            guarded {
                val api = api()
                val hadPending = riverDao.pendingSections().isNotEmpty()
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
                if (hadPending && !loggedIn) {
                    needLogin("wysłać dodane trasy na serwer")
                } else {
                    SyncOutcome(true, "Trasy: wysłano $pushed, pobrano $added.")
                }
            }
        }
    }

    /** Wysyła lokalnie dodane odcinki; zwraca liczbę wysłanych. Wołać tylko pod [mutex]. */
    private suspend fun pushPendingSections(api: KajakServerApi): Int {
        if (!loggedIn) return 0 // dodawanie tras wymaga konta
        var count = 0
        for (section in riverDao.pendingSections()) {
            val river = riverDao.getRiver(section.riverId) ?: continue
            val dto = try {
                api.createRoute(
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
            } catch (e: HttpException) {
                // Odrzucona trasa (400) nie może blokować pozostałych ani pobierania cudzych tras.
                if (e.code() == 400) continue else throw e
            }
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
                    ?: return@guarded if (loggedIn) {
                        SyncOutcome(false, "Odcinek nie jest jeszcze na serwerze.")
                    } else {
                        needLogin("wysłać tę trasę na serwer")
                    }

                if (loggedIn) pushObstacles(api, key, sectionId)
                pullObstacles(api, key, sectionId)
                val hasPending = obstacleDao.listForSection(sectionId).any {
                    it.serverId == null || it.pendingConfirms > 0 || it.pendingRemovals > 0
                }
                if (hasPending && !loggedIn) {
                    needLogin("wysłać swoje zgłoszenia i głosy")
                } else {
                    SyncOutcome(true, "")
                }
            }
        }
    }

    private suspend fun pushObstacles(api: KajakServerApi, sectionKey: String, sectionId: Long) {
        for (stored in obstacleDao.listForSection(sectionId)) {
            var cur = stored
            if (cur.serverId == null) {
                val dto = try {
                    api.createObstacle(
                        sectionKey,
                        ObstacleRequest(
                            clientId = settings.clientId("o", cur.id),
                            type = cur.type.name,
                            description = cur.description,
                            lat = cur.lat,
                            lon = cur.lon
                        )
                    )
                } catch (e: HttpException) {
                    // Odrzucone zgłoszenie (400) zostaje lokalnie i nie blokuje pozostałych.
                    if (e.code() == 400) continue else throw e
                }
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
     * Pierwsze wywołanie udostępnia spływ na serwerze (twórcą zostaje zalogowany użytkownik),
     * kolejne wysyłają nowe elementy i pobierają zmiany innych osób. Jeśli spływ został usunięty
     * albo użytkownik przestał w nim uczestniczyć, lokalna kopia jest usuwana.
     */
    suspend fun syncTrip(tripId: Long): SyncOutcome {
        if (!enabled) return notEnabled
        val user = username ?: return needLogin("udostępnić spływ i synchronizować go z serwerem")
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
                            notes = trip.notes
                        )
                    )
                    // Organizatora wpisanego ręcznie przed zalogowaniem zastępuje konto.
                    tripDao.participantsOf(tripId)
                        .firstOrNull { it.serverId == null && it.name.equals(trip.organizer, ignoreCase = true) }
                        ?.let { tripDao.updateParticipant(it.copy(name = user)) }
                    trip = trip.copy(serverId = created.id, organizer = user, ownerUsername = user)
                    tripDao.updateTrip(trip)
                }
                val serverTripId = trip.serverId
                    ?: return@guarded SyncOutcome(false, "Spływ nie ma identyfikatora na serwerze.")

                val remote = fetchTripOrNull(api, serverTripId)
                if (remote == null) {
                    tripDao.deleteTrip(tripId)
                    return@guarded SyncOutcome(true, REMOVED_MESSAGE)
                }
                pushTripChildren(api, tripId, serverTripId, user, remote)
                mergeTrip(tripId, api.trip(serverTripId))
                SyncOutcome(true, "Spływ jest zsynchronizowany z serwerem.")
            }
        }
    }

    /** Synchronizuje spływ wskazany identyfikatorem z serwera (zdarzenia „na żywo”); nieznane pomija. */
    suspend fun syncTripByServerId(serverId: Long): SyncOutcome {
        if (!enabled || !loggedIn) return disabled
        val local = tripDao.findTripByServerId(serverId) ?: return disabled
        return syncTrip(local.id)
    }

    /** Odświeża przeszkody odcinka wskazanego kluczem z serwera; nieznane odcinki pomija. */
    suspend fun syncObstaclesByKey(sectionKey: String): SyncOutcome {
        if (!enabled) return disabled
        val section = riverDao.findSectionByKey(sectionKey) ?: return disabled
        return syncObstacles(section.id)
    }

    /** Synchronizuje wszystkie spływy udostępnione na serwerze (wykrywa usunięcia i wyjścia). */
    suspend fun syncSharedTrips(): SyncOutcome {
        if (!enabled || !loggedIn) return SyncOutcome(true, "")
        var removed = 0
        var failure: SyncOutcome? = null
        for (id in tripDao.sharedTripIds()) {
            val result = syncTrip(id)
            if (result.message == REMOVED_MESSAGE) {
                removed++
            } else if (!result.ok && failure == null) {
                failure = result
            }
        }
        return when {
            removed > 0 -> SyncOutcome(true, "Usunięto z telefonu spływy, które już nie istnieją lub w których nie uczestniczysz: $removed.")
            failure != null -> failure
            else -> SyncOutcome(true, "")
        }
    }

    /** Spływ z serwera albo null, gdy go nie ma (404) lub nie jesteśmy już jego uczestnikiem (403). */
    private suspend fun fetchTripOrNull(api: KajakServerApi, serverTripId: Long): TripDetailDto? =
        try {
            api.trip(serverTripId)
        } catch (e: HttpException) {
            // Za „spływ zniknął” uznajemy tylko odpowiedź w formacie naszego serwera ({"error": ...}),
            // żeby goły 404 z proxy albo innego serwera nie kasował lokalnych danych.
            val fromOurServer = (e.code() == 404 || e.code() == 403) &&
                runCatching { e.response()?.errorBody()?.string() }.getOrNull()
                    ?.let { ERROR_FIELD.containsMatchIn(it) } == true
            if (fromOurServer) null else throw e
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

    /**
     * Wysyła lokalne elementy spływu. Na serwer trafiają tylko: użytkownik jako uczestnik
     * (nikogo innego nie można dodać), jego zameldowania oraz wyposażenie. Pozostałe osoby
     * wpisane lokalnie zostają tylko na tym telefonie.
     */
    private suspend fun pushTripChildren(
        api: KajakServerApi,
        tripId: Long,
        serverTripId: Long,
        user: String,
        remote: TripDetailDto
    ) {
        for (p in tripDao.participantsOf(tripId)) {
            if (p.serverId != null || !p.name.equals(user, ignoreCase = true)) continue
            val dto = api.addParticipant(serverTripId, ParticipantRequest(p.carSeats, p.needsKayak))
            tripDao.updateParticipant(p.copy(name = dto.name, serverId = dto.id))
        }

        val knownNames = remote.participants.map { it.name }.toMutableList()
        if (knownNames.none { it.equals(user, ignoreCase = true) }) knownNames += user
        val linkedGear = tripDao.gearOf(tripId).mapNotNull { it.serverId }.toMutableSet()
        for (g in tripDao.gearOf(tripId)) {
            if (g.serverId != null) continue
            val assignee = g.assignedTo?.let { name -> knownNames.firstOrNull { it.equals(name, ignoreCase = true) } }
            val match = remote.gear.firstOrNull {
                it.id !in linkedGear && it.name.equals(g.name, ignoreCase = true)
            }
            val remoteId = if (match != null) {
                match.id
            } else {
                val created = api.addGear(serverTripId, GearRequest(g.name, assignee))
                if (g.packed) api.patchGear(serverTripId, created.id, GearPatchRequest(assignee, true))
                created.id
            }
            linkedGear += remoteId
            tripDao.updateGear(g.copy(serverId = remoteId))
        }

        for (c in tripDao.checkInsOf(tripId)) {
            if (c.serverId != null || !c.personName.equals(user, ignoreCase = true)) continue
            val dto = api.addCheckIn(
                serverTripId,
                CheckInRequest(
                    clientId = settings.clientId("c", c.id),
                    personName = user,
                    lat = c.lat,
                    lon = c.lon,
                    fixAt = Instant.ofEpochMilli(c.fixAt).toString(),
                    needsHelp = c.needsHelp
                )
            )
            tripDao.updateCheckIn(c.copy(serverId = dto.id, pendingSync = false))
        }
    }

    /** Wysyła zmienione dane własnego uczestnika (miejsca w aucie, kajak) w udostępnionym spływie. */
    suspend fun pushMyParticipant(tripId: Long): SyncOutcome {
        if (!enabled) return SyncOutcome(true, "")
        val user = username ?: return needLogin("wysłać swoje dane do spływu")
        return mutex.withLock {
            guarded {
                val trip = tripDao.getTrip(tripId)
                val serverTripId = trip?.serverId ?: return@guarded SyncOutcome(true, "")
                val me = tripDao.participantsOf(tripId)
                    .firstOrNull { it.name.equals(user, ignoreCase = true) }
                    ?: return@guarded SyncOutcome(true, "")
                val dto = api().addParticipant(serverTripId, ParticipantRequest(me.carSeats, me.needsKayak))
                tripDao.updateParticipant(me.copy(serverId = dto.id))
                SyncOutcome(true, "")
            }
        }
    }

    /**
     * Usuwa spływ z serwera przed usunięciem go z telefonu. Organizator może skasować spływ dla wszystkich
     * albo (gdy jest drugi organizator) tylko go opuścić; pozostali uczestnicy mogą go tylko opuścić
     * (usuwają siebie). Spływ tylko lokalny nie wymaga serwera.
     */
    suspend fun removeTripFromServer(tripId: Long, leaveOnly: Boolean): SyncOutcome {
        // Stan spływu czytamy pod blokadą, żeby nie zdublować się z trwającym udostępnianiem.
        return mutex.withLock {
            val trip = tripDao.getTrip(tripId) ?: return@withLock SyncOutcome(true, "")
            val serverTripId = trip.serverId ?: return@withLock SyncOutcome(true, "")
            if (!enabled) return@withLock notEnabled
            val user = username
                ?: return@withLock needLogin("usunąć lub opuścić udostępniony spływ")
            guarded {
                val api = api()
                val iAmOrganizer = tripDao.participantsOf(tripId)
                    .any { it.isOrganizer && it.name.equals(user, ignoreCase = true) }
                if (iAmOrganizer && !leaveOnly) {
                    val response = api.deleteTrip(serverTripId)
                    if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
                } else {
                    val me = tripDao.participantsOf(tripId)
                        .firstOrNull { it.serverId != null && it.name.equals(user, ignoreCase = true) }
                    val myServerId = me?.serverId
                    if (myServerId != null) {
                        val response = api.deleteParticipant(serverTripId, myServerId)
                        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
                    } else if (fetchTripOrNull(api, serverTripId) != null) {
                        // Serwer nadal widzi nas w spływie, a nie wiemy, który wpis jest nasz – nie usuwamy samego telefonu.
                        return@guarded SyncOutcome(false, "Nie udało się ustalić Twojego udziału na serwerze – zsynchronizuj spływ i spróbuj ponownie.")
                    }
                }
                SyncOutcome(true, "")
            }
        }
    }

    /** Mianuje uczestnika (lokalne id) organizatorem; wymaga, żeby sam był już na serwerze. */
    suspend fun makeOrganizer(tripId: Long, participantId: Long): SyncOutcome {
        if (!enabled) return notEnabled
        if (!loggedIn) return needLogin("mianować organizatora")
        return mutex.withLock {
            guarded {
                val trip = tripDao.getTrip(tripId)
                val serverTripId = trip?.serverId
                    ?: return@guarded SyncOutcome(false, "Spływ nie jest udostępniony na serwerze.")
                val target = tripDao.participantsOf(tripId).firstOrNull { it.id == participantId }
                val targetServerId = target?.serverId
                    ?: return@guarded SyncOutcome(false, "Ta osoba nie jest jeszcze na serwerze.")
                api().promoteParticipant(serverTripId, targetServerId)
                mergeTrip(tripId, api().trip(serverTripId))
                SyncOutcome(true, "${target.name} jest teraz organizatorem.")
            }
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
                    ownerUsername = detail.trip.organizer.ifBlank { trip.ownerUsername },
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
                        local.copy(
                            name = r.name,
                            carSeats = r.carSeats,
                            needsKayak = r.needsKayak,
                            isOrganizer = r.isOrganizer
                        )
                    )
                } else {
                    tripDao.insertParticipant(
                        ParticipantEntity(
                            tripId = tripId,
                            name = r.name,
                            carSeats = r.carSeats,
                            needsKayak = r.needsKayak,
                            serverId = r.id,
                            isOrganizer = r.isOrganizer
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
                val existing = localCheckIns.firstOrNull { it.serverId == r.id }
                if (existing != null) {
                    // Wezwanie pomocy mógł odwołać jego autor na innym telefonie.
                    if (existing.needsHelp != r.needsHelp) tripDao.setCheckInHelp(existing.id, r.needsHelp)
                    continue
                }
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
        if (!enabled) return RemoteTripsResult(notEnabled, emptyList())
        if (!loggedIn) return RemoteTripsResult(needLogin("zobaczyć spływy na serwerze"), emptyList())
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

    /**
     * Dołącza zalogowanego użytkownika do spływu z serwera (jako siebie) i dodaje spływ
     * do lokalnej bazy. Zwraca lokalne id spływu.
     */
    suspend fun joinTrip(remoteTripId: Long): JoinResult {
        if (!enabled) return JoinResult(notEnabled, null)
        if (!loggedIn) return JoinResult(needLogin("dołączyć do spływu"), null)
        var localId: Long? = null
        val outcome = mutex.withLock {
            guarded {
                tripDao.findTripByServerId(remoteTripId)?.let {
                    localId = it.id
                    return@guarded SyncOutcome(true, "Ten spływ już masz na liście.")
                }
                val api = api()
                // Gdy już jesteśmy uczestnikiem, nie nadpisujemy swoich danych ponownym dołączeniem.
                val detail = try {
                    fetchTripOrNull(api, remoteTripId) ?: run {
                        api.addParticipant(remoteTripId, ParticipantRequest(carSeats = 0, needsKayak = false))
                        api.trip(remoteTripId)
                    }
                } catch (e: HttpException) {
                    if (e.code() == 404) return@guarded SyncOutcome(false, "Ten spływ już nie istnieje.")
                    throw e
                }
                val sectionId = detail.trip.sectionKey?.let { riverDao.findSectionByKey(it)?.id }
                val id = tripDao.insertTrip(
                    TripEntity(
                        title = detail.trip.title,
                        sectionId = sectionId,
                        startDateUtcMillis = fromIsoDate(detail.trip.startDate, System.currentTimeMillis()),
                        overnight = detail.trip.overnight,
                        organizer = detail.trip.organizer,
                        notes = detail.trip.notes,
                        serverId = remoteTripId,
                        ownerUsername = detail.trip.organizer
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
                // Serwer przyjmuje tylko uczestników z konta; osoba wpisana lokalnie nie może być przypisana.
                val assignee = gear.assignedTo?.takeIf { name ->
                    tripDao.participantsOf(gear.tripId)
                        .any { it.serverId != null && it.name.equals(name, ignoreCase = true) }
                }
                api().patchGear(serverTripId, serverGearId, GearPatchRequest(assignee, gear.packed))
                SyncOutcome(true, "")
            }
        }
    }

    /** Wysyła własną pozycję uczestnikom spływu. Zwraca null, gdy się udało, albo opis problemu. */
    suspend fun pushLocation(localTripId: Long, lat: Double, lon: Double, fixAt: Long): String? {
        if (!enabled) return "Serwer jest wyłączony w ustawieniach."
        if (!loggedIn) return "Zaloguj się, aby udostępniać pozycję."
        return try {
            val serverTripId = tripDao.getTrip(localTripId)?.serverId
                ?: return "Ten spływ nie jest jeszcze na serwerze."
            val response = api().putLocation(serverTripId, LocationRequest(lat, lon, Instant.ofEpochMilli(fixAt).toString()))
            if (response.isSuccessful) null else locationProblem(response.code())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            "Brak połączenia z serwerem."
        } catch (e: Exception) {
            "Nie udało się wysłać pozycji (${e.javaClass.simpleName})."
        }
    }

    /** Pozycje uczestników spływu albo opis problemu (np. serwer bez obsługi pozycji, brak sieci). */
    suspend fun fetchLocations(localTripId: Long): LocationsResult {
        if (!enabled) return LocationsResult(error = "Serwer jest wyłączony w ustawieniach.")
        if (!loggedIn) return LocationsResult(error = "Zaloguj się, aby widzieć innych uczestników.")
        return try {
            val serverTripId = tripDao.getTrip(localTripId)?.serverId
                ?: return LocationsResult(error = "Ten spływ nie jest jeszcze na serwerze.")
            LocationsResult(
                people = api().locations(serverTripId).map {
                    PersonOnMap(
                        name = it.username,
                        lat = it.lat,
                        lon = it.lon,
                        needsHelp = it.needsHelp,
                        helpCheckInId = it.helpCheckInId,
                        updatedAt = parseTime(it.updatedAt, System.currentTimeMillis())
                    )
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            LocationsResult(error = locationProblem(e.code()))
        } catch (e: IOException) {
            LocationsResult(error = "Brak połączenia z serwerem.")
        } catch (e: Exception) {
            LocationsResult(error = "Nie udało się odczytać pozycji (${e.javaClass.simpleName}).")
        }
    }

    /**
     * Osoby, które według zameldowań zapisanych na telefonie wzywają pomocy (najnowsze zameldowanie
     * każdej osoby). Działa bez sieci i bez obsługi pozycji na serwerze.
     */
    suspend fun localHelpPeople(localTripId: Long): List<PersonOnMap> =
        tripDao.checkInsOf(localTripId)
            .groupBy { it.personName.lowercase() }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.createdAt }?.takeIf { it.needsHelp } }
            .filter { System.currentTimeMillis() - it.createdAt <= LOCAL_HELP_MAX_AGE_MS }
            .map {
                PersonOnMap(it.personName, it.lat, it.lon, true, it.serverId ?: 0L, it.createdAt)
            }

    private fun locationProblem(code: Int): String = when (code) {
        401 -> "Sesja wygasła – zaloguj się ponownie."
        403 -> "Nie jesteś uczestnikiem tego spływu na serwerze."
        404 -> "Serwer nie zna tego spływu albo nie obsługuje pozycji na żywo – zaktualizuj serwer."
        else -> "Błąd serwera (HTTP $code)."
    }

    /**
     * Odwołuje wezwanie pomocy. W spływie na serwerze najpierw odwołuje je serwer (tylko autor może to
     * zrobić) i dopiero potem telefon; w spływie lokalnym wystarczy zmiana na telefonie.
     */
    suspend fun cancelHelp(checkInId: Long): SyncOutcome {
        val checkIn = tripDao.getCheckIn(checkInId) ?: return SyncOutcome(true, "")
        val serverId = checkIn.serverId
        val serverTripId = tripDao.getTrip(checkIn.tripId)?.serverId
        if (serverId == null || serverTripId == null) {
            tripDao.setCheckInHelp(checkInId, false)
            return SyncOutcome(true, "")
        }
        if (!enabled) return notEnabled
        return mutex.withLock {
            guarded {
                api().patchCheckIn(serverTripId, serverId, CheckInHelpRequest(false))
                tripDao.setCheckInHelp(checkInId, false)
                SyncOutcome(true, "")
            }
        }
    }

    /** Odwołuje własne wezwanie pomocy rozpoznane po id zameldowania na serwerze (z listy pozycji). */
    suspend fun cancelHelpByServerId(localTripId: Long, serverCheckInId: Long): SyncOutcome {
        if (serverCheckInId <= 0) {
            // Wezwanie jeszcze nie dotarło na serwer – odwołujemy własne zameldowanie z telefonu.
            val mine = tripDao.checkInsOf(localTripId)
                .filter { it.needsHelp && it.serverId == null && it.personName.equals(username, ignoreCase = true) }
            if (mine.isEmpty()) return SyncOutcome(false, "Nie znaleziono wezwania do odwołania.")
            return mine.map { cancelHelp(it.id) }.firstOrNull { !it.ok } ?: SyncOutcome(true, "")
        }
        if (!enabled) return notEnabled
        return mutex.withLock {
            guarded {
                val serverTripId = tripDao.getTrip(localTripId)?.serverId ?: return@guarded disabled
                api().patchCheckIn(serverTripId, serverCheckInId, CheckInHelpRequest(false))
                tripDao.findCheckInByServerId(serverCheckInId)?.let { tripDao.setCheckInHelp(it.id, false) }
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
            if (e.code() == 401 && loggedIn) {
                // Token wygasł albo został unieważniony – wymagane ponowne logowanie.
                settings.clearSession()
            }
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
            401 -> "Sesja wygasła lub nie jesteś zalogowany – zaloguj się ponownie."
            403 -> "Serwer odmówił: " + (detail ?: "brak uprawnień do tej operacji.")
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
        const val LOCAL_HELP_MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val REMOVED_MESSAGE =
            "Ten spływ został usunięty lub nie jesteś już jego uczestnikiem – usunięto go z telefonu."
        val ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"([^\"]*)\"")
    }
}

package pl.kajakapp.data

import java.io.BufferedReader
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import pl.kajakapp.data.remote.Network

/** Zdarzenie przysłane przez serwer (nazwa + surowe dane JSON). */
data class LiveEvent(val name: String, val data: String)

/**
 * Aktualizacje „na żywo”: trzyma otwarte połączenie SSE z serwerem (GET api/events), gdy aplikacja jest
 * na ekranie, użytkownik jest zalogowany, a adres serwera ustawiony. Serwer wysyła tylko informację
 * „coś się zmieniło” (np. spływ nr 5); aplikacja pobiera wtedy świeże dane zwykłą synchronizacją,
 * więc obowiązują te same uprawnienia co zawsze. Po zerwaniu połączenia próbuje ponownie
 * (z coraz dłuższą przerwą) i po każdym połączeniu nadrabia zaległości pełną synchronizacją.
 */
class LiveUpdates(
    private val settings: ServerSettings,
    private val sync: SyncRepository,
    scope: CoroutineScope
) {
    private val foreground = MutableStateFlow(false)

    private val _trips = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emituje, gdy na serwerze zmieniła się lista spływów (nowy albo usunięty). */
    val tripsChanged: SharedFlow<Unit> = _trips

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    init {
        scope.launch {
            combine(settings.url, settings.session, foreground) { url, session, fg ->
                if (fg && url.isNotEmpty() && session != null) url to session.token else null
            }.distinctUntilChanged().collectLatest { target ->
                if (target != null) connectForever(target.first, target.second)
            }
        }
    }

    private suspend fun connectForever(baseUrl: String, token: String) {
        var pause = MIN_PAUSE_MS
        while (true) {
            val started = System.currentTimeMillis()
            val unauthorized = try {
                stream(baseUrl, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (unauthorized) {
                // Token wygasł – ponowne logowanie zmieni sesję i uruchomi połączenie od nowa.
                settings.clearSession()
                return
            }
            // Połączenie, które trwało dłużej, resetuje czas oczekiwania.
            pause = if (System.currentTimeMillis() - started > STABLE_MS) MIN_PAUSE_MS else minOf(pause * 2, MAX_PAUSE_MS)
            delay(pause)
        }
    }

    /** Jedno połączenie. Zwraca true, gdy serwer odrzucił token (401). */
    private suspend fun stream(baseUrl: String, token: String): Boolean = coroutineScope {
        val request = Request.Builder()
            .url(baseUrl + "api/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        val call = Network.streamClient.newCall(request)
        // Anulowanie korutyny przerywa blokujące czytanie z gniazda.
        val watcher = launch(start = CoroutineStart.ATOMIC) { try { awaitCancellation() } finally { call.cancel() } }
        val queue = Channel<LiveEvent>(Channel.UNLIMITED)
        val worker = launch { process(queue) }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (response.code == 401) return@withContext true
                    val body = response.body
                    if (!response.isSuccessful || body == null) throw IOException("HTTP ${response.code}")
                    // Po (ponownym) połączeniu nadrabiamy to, co mogło umknąć.
                    queue.trySend(LiveEvent(CATCH_UP, ""))
                    readEvents(body.charStream().buffered()) { queue.trySend(it) }
                    false
                }
            }
        } finally {
            queue.close()
            watcher.cancel()
            // Po zwykłym zerwaniu łącza dokańczamy trwającą synchronizację; przy anulowaniu join i tak się przerwie.
            if (!worker.isCompleted) worker.join()
        }
    }

    private fun readEvents(reader: BufferedReader, onEvent: (LiveEvent) -> Unit) {
        var name = ""
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: return
            when {
                line.isEmpty() -> {
                    if (name.isNotEmpty()) onEvent(LiveEvent(name, data.toString()))
                    name = ""
                    data.setLength(0)
                }
                line.startsWith(":") -> Unit // komentarz / sygnał życia
                line.startsWith("event:") -> name = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trim())
                }
            }
        }
    }

    /** Łączy kilka zdarzeń w jedną synchronizację (np. seria zmian w tym samym spływie). */
    private suspend fun process(queue: Channel<LiveEvent>) {
        for (first in queue) {
            val batch = mutableListOf(first)
            while (true) batch += queue.tryReceive().getOrNull() ?: break
            try {
                handle(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Błąd jednej synchronizacji nie może zatrzymać nasłuchiwania.
            }
        }
    }

    private suspend fun handle(batch: List<LiveEvent>) {
        if (batch.any { it.name == CATCH_UP }) {
            sync.syncRoutes()
            sync.syncSharedTrips()
            _trips.tryEmit(Unit)
        }
        val tripIds = linkedSetOf<Long>()
        val sections = linkedSetOf<String>()
        var routes = false
        var tripList = false
        for (e in batch) {
            when (e.name) {
                "trip" -> number(e.data, "trip_id")?.let { tripIds += it }
                "trips" -> tripList = true
                "routes" -> routes = true
                "obstacles" -> text(e.data, "section_key")?.let { sections += it }
            }
        }
        if (routes) sync.syncRoutes()
        for (id in tripIds) sync.syncTripByServerId(id)
        for (key in sections) sync.syncObstaclesByKey(key)
        if (tripList || tripIds.isNotEmpty()) _trips.tryEmit(Unit)
    }

    private fun number(json: String, field: String): Long? =
        runCatching { Json.parseToJsonElement(json).jsonObject[field]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun text(json: String, field: String): String? =
        runCatching { Json.parseToJsonElement(json).jsonObject[field]?.jsonPrimitive?.content }.getOrNull()

    private companion object {
        const val CATCH_UP = "catch-up"
        const val MIN_PAUSE_MS = 2_000L
        const val MAX_PAUSE_MS = 60_000L
        const val STABLE_MS = 30_000L
    }
}

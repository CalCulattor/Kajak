package pl.kajakapp.data

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.kajakapp.data.db.CacheDao
import pl.kajakapp.data.db.SectionEntity
import pl.kajakapp.data.db.WaterCacheEntity
import pl.kajakapp.data.db.WeatherCacheEntity
import pl.kajakapp.data.remote.ImgwApi
import pl.kajakapp.data.remote.ImgwHydroDto
import pl.kajakapp.data.remote.OpenMeteoApi
import pl.kajakapp.domain.ForecastRange
import pl.kajakapp.domain.ForecastWindow
import pl.kajakapp.domain.StationCandidate
import pl.kajakapp.domain.StationMatcher
import pl.kajakapp.domain.WaterReading
import pl.kajakapp.domain.WeatherSnapshot

enum class FetchStatus { OK, ERROR, NO_STATION, NOT_FOUND }

data class RefreshResult(val water: FetchStatus, val weather: FetchStatus)

enum class ForecastStatus { OK, PAST, TOO_FAR, NO_DATA, ERROR }

/** Wynik zapytania o prognozę na termin; [weather] jest ustawione tylko przy [ForecastStatus.OK]. */
data class ForecastFetch(val status: ForecastStatus, val weather: WeatherSnapshot? = null)

/**
 * Pobiera stan wody (IMGW) i pogodę (Open-Meteo) i zapisuje ostatni wynik w bazie,
 * dzięki czemu aplikacja pokazuje dane także bez zasięgu (z godziną pobrania).
 */
class ConditionsRepository(
    private val imgw: ImgwApi,
    private val openMeteo: OpenMeteoApi,
    private val cache: CacheDao
) {
    fun observeWater(sectionId: Long): Flow<WaterReading?> =
        cache.observeWater(sectionId).map { it?.toDomain() }

    fun observeWeather(sectionId: Long): Flow<WeatherSnapshot?> =
        cache.observeWeather(sectionId).map { it?.toDomain() }

    suspend fun refresh(section: SectionEntity, riverName: String): RefreshResult = coroutineScope {
        val water = async { refreshWater(section, riverName) }
        val weather = async { refreshWeather(section) }
        RefreshResult(water.await(), weather.await())
    }

    /**
     * Stan wody z IMGW. Gdy odcinek ma wpisany wodowskaz, szukamy go po nazwie (bez względu na wielkość liter
     * i polskie znaki); gdy nie ma – wybieramy najbliższą stację na tej samej rzece.
     */
    private suspend fun refreshWater(section: SectionEntity, riverName: String): FetchStatus {
        val stationName = section.stationName?.trim().orEmpty()
        return try {
            val stations = imgw.hydro()
            val candidates = stations.map {
                StationCandidate(
                    name = it.station.orEmpty(),
                    river = it.river.orEmpty(),
                    lat = it.lat.toCoordinate(),
                    lon = it.lon.toCoordinate(),
                    hasLevel = it.waterLevel.toDoubleOrNullPl() != null
                )
            }
            val match = StationMatcher.pick(
                stations = candidates,
                stationName = stationName.ifEmpty { null },
                riverName = riverName,
                lat = section.lat,
                lon = section.lon,
                hints = listOf(section.name, section.putIn, section.takeOut)
            )
            if (match == null) {
                cache.clearWater(section.id)
                return if (stationName.isEmpty()) FetchStatus.NO_STATION else FetchStatus.NOT_FOUND
            }
            val dto: ImgwHydroDto = stations[match.index]

            cache.upsertWater(
                WaterCacheEntity(
                    sectionId = section.id,
                    stationName = dto.station ?: stationName,
                    levelCm = dto.waterLevel.toDoubleOrNullPl()?.roundToInt(),
                    levelMeasuredAt = dto.waterLevelDate,
                    measuredAtMillis = parseImgwTime(dto.waterLevelDate),
                    flowM3s = dto.flow.toDoubleOrNullPl(),
                    waterTempC = dto.waterTemp.toDoubleOrNullPl(),
                    iceActive = dto.ice.isIcePhenomenon(),
                    warningCm = dto.warningLevel.toDoubleOrNullPl()?.roundToInt(),
                    alarmCm = dto.alarmLevel.toDoubleOrNullPl()?.roundToInt(),
                    fetchedAt = System.currentTimeMillis()
                )
            )
            FetchStatus.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchStatus.ERROR
        }
    }

    private suspend fun refreshWeather(section: SectionEntity): FetchStatus {
        return try {
            val resp = openMeteo.forecast(section.lat, section.lon)
            val gust = listOfNotNull(
                resp.current?.windGusts,
                resp.daily?.windGustsMax?.firstOrNull()
            ).maxOrNull()
            val codes = listOfNotNull(
                resp.current?.weatherCode,
                resp.daily?.weatherCode?.firstOrNull()
            )
            cache.upsertWeather(
                WeatherCacheEntity(
                    sectionId = section.id,
                    airTempC = resp.current?.temperature,
                    windGustMs = gust,
                    precipitationMm = resp.daily?.precipitationSum?.firstOrNull(),
                    thunderstorm = codes.any { it in THUNDERSTORM_CODES },
                    fetchedAt = System.currentTimeMillis()
                )
            )
            FetchStatus.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchStatus.ERROR
        }
    }

    /**
     * Prognoza pogody na wybrany termin: okno [hours] godzin od [start] (czas lokalny, Europa/Warszawa).
     * Wyniki są pamiętane przez kilka minut, żeby przełączanie terminów nie odpytywało serwera za każdym razem.
     */
    suspend fun forecast(
        lat: Double,
        lon: Double,
        start: LocalDateTime,
        hours: Int = ForecastWindow.DEFAULT_HOURS
    ): ForecastFetch {
        val now = LocalDateTime.now(IMGW_ZONE)
        when (ForecastWindow.range(start, now)) {
            ForecastRange.PAST -> return ForecastFetch(ForecastStatus.PAST)
            ForecastRange.TOO_FAR -> return ForecastFetch(ForecastStatus.TOO_FAR)
            ForecastRange.OK -> Unit
        }
        val key = "%.3f,%.3f|%s|%d".format(java.util.Locale.ROOT, lat, lon, start.withMinute(0).withSecond(0).withNano(0), hours)
        val nowMillis = System.currentTimeMillis()
        forecastCache[key]?.let { (at, snapshot) ->
            if (nowMillis - at < FORECAST_CACHE_MS) return ForecastFetch(ForecastStatus.OK, snapshot)
        }
        return try {
            val startDate = start.toLocalDate()
            // Okno może przejść przez północ, więc pobieramy też następny dzień.
            val resp = openMeteo.hourly(
                latitude = lat,
                longitude = lon,
                startDate = startDate.toString(),
                endDate = startDate.plusDays(1).toString()
            )
            val h = resp.hourly ?: return ForecastFetch(ForecastStatus.NO_DATA)
            val snapshot = ForecastWindow.summarize(
                times = h.time,
                temperature = h.temperature,
                precipitation = h.precipitation,
                gusts = h.windGusts,
                codes = h.weatherCode,
                start = start,
                hours = hours,
                fetchedAt = nowMillis
            ) ?: return ForecastFetch(ForecastStatus.NO_DATA)
            forecastCache[key] = nowMillis to snapshot
            ForecastFetch(ForecastStatus.OK, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ForecastFetch(ForecastStatus.ERROR)
        }
    }

    private val forecastCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, WeatherSnapshot>>()

    private companion object {
        // Kody pogody WMO używane przez Open-Meteo: burza (95) oraz burza z gradem (96, 99).
        val THUNDERSTORM_CODES = setOf(95, 96, 99)
        const val FORECAST_CACHE_MS = 10L * 60L * 1000L
    }
}

private val IMGW_ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

/**
 * IMGW podaje czas lokalny jako "2026-10-02 16:20:00" (czasem bez sekund albo w formacie ISO ze strefą).
 * Zwraca null, gdy tekstu nie da się odczytać – wtedy aplikacja pokazuje pomiar bez daty.
 */
internal fun parseImgwTime(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val iso = text.replace(' ', 'T')
    return try {
        if (iso.endsWith("Z") || Regex(""".*[+-]\d{2}:?\d{2}$""").matches(iso)) {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } else {
            // "2026-10-02T16:20" (bez sekund) też jest poprawne dla LocalDateTime.parse.
            LocalDateTime.parse(iso).atZone(IMGW_ZONE).toInstant().toEpochMilli()
        }
    } catch (e: DateTimeParseException) {
        null
    }
}

/** Współrzędna z danych IMGW (tekst z kropką lub przecinkiem); null dla braku albo wartości spoza zakresu. */
private fun String?.toCoordinate(): Double? =
    this?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 && it != 0.0 }

private fun String?.toDoubleOrNullPl(): Double? =
    this?.trim()?.replace(',', '.')?.toDoubleOrNull()

/** IMGW zwraca "0" gdy brak zjawisk lodowych, a niezerowy kod gdy są. */
private fun String?.isIcePhenomenon(): Boolean {
    val v = this?.trim() ?: return false
    return v.isNotEmpty() && v != "0"
}

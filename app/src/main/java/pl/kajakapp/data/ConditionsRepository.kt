package pl.kajakapp.data

import java.time.LocalDateTime
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
import pl.kajakapp.data.remote.OpenMeteoApi
import pl.kajakapp.domain.WaterReading
import pl.kajakapp.domain.WeatherSnapshot

enum class FetchStatus { OK, ERROR, NO_STATION, NOT_FOUND }

data class RefreshResult(val water: FetchStatus, val weather: FetchStatus)

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

    private suspend fun refreshWater(section: SectionEntity, riverName: String): FetchStatus {
        val stationName = section.stationName?.trim().orEmpty()
        if (stationName.isEmpty()) {
            cache.clearWater(section.id)
            return FetchStatus.NO_STATION
        }
        return try {
            val byName = imgw.hydro().filter { it.station.equals(stationName, ignoreCase = true) }
            val dto = byName.firstOrNull { it.river.equals(riverName, ignoreCase = true) }
                ?: byName.singleOrNull()
                ?: return FetchStatus.NOT_FOUND

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

    private companion object {
        // Kody pogody WMO używane przez Open-Meteo: burza (95) oraz burza z gradem (96, 99).
        val THUNDERSTORM_CODES = setOf(95, 96, 99)
    }
}

private val IMGW_ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

/** IMGW podaje czas lokalny w formacie "2026-10-02 16:20:00". */
internal fun parseImgwTime(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        LocalDateTime.parse(text.replace(' ', 'T')).atZone(IMGW_ZONE).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}

private fun String?.toDoubleOrNullPl(): Double? =
    this?.trim()?.replace(',', '.')?.toDoubleOrNull()

/** IMGW zwraca "0" gdy brak zjawisk lodowych, a niezerowy kod gdy są. */
private fun String?.isIcePhenomenon(): Boolean {
    val v = this?.trim() ?: return false
    return v.isNotEmpty() && v != "0"
}

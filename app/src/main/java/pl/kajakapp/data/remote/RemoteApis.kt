package pl.kajakapp.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Publiczne API IMGW. Wszystkie wartości przychodzą jako teksty (lub null).
 * https://danepubliczne.imgw.pl/api/data/hydro
 */
@Serializable
data class ImgwHydroDto(
    @SerialName("id_stacji") val stationId: String? = null,
    @SerialName("stacja") val station: String? = null,
    @SerialName("rzeka") val river: String? = null,
    @SerialName("stan_ostrzegawczy") val warningLevel: String? = null,
    @SerialName("stan_alarmowy") val alarmLevel: String? = null,
    @SerialName("stan_wody") val waterLevel: String? = null,
    @SerialName("stan_wody_data_pomiaru") val waterLevelDate: String? = null,
    @SerialName("temperatura_wody") val waterTemp: String? = null,
    @SerialName("przeplyw") val flow: String? = null,
    @SerialName("zjawisko_lodowe") val ice: String? = null
)

interface ImgwApi {
    @GET("api/data/hydro")
    suspend fun hydro(): List<ImgwHydroDto>
}

@Serializable
data class OpenMeteoResponse(
    val current: OpenMeteoCurrent? = null,
    val daily: OpenMeteoDaily? = null
)

@Serializable
data class OpenMeteoCurrent(
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("wind_gusts_10m") val windGusts: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null
)

@Serializable
data class OpenMeteoDaily(
    @SerialName("precipitation_sum") val precipitationSum: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m_max") val windGustsMax: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList()
)

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,wind_gusts_10m,weather_code",
        @Query("daily") daily: String = "precipitation_sum,wind_gusts_10m_max,weather_code",
        @Query("wind_speed_unit") windSpeedUnit: String = "ms",
        @Query("forecast_days") forecastDays: Int = 1,
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponse
}

object Network {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun retrofit(baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Volatile
    private var serverCache: Pair<String, KajakServerApi>? = null

    /**
     * Klient serwera KajakApp dla podanego adresu bazowego (musi kończyć się znakiem „/”).
     * [token] jest czytany przy każdym żądaniu, więc logowanie i wylogowanie działa bez
     * odtwarzania klienta. Klient jest zapamiętywany (zakładamy jeden dostawca tokenu).
     */
    fun server(baseUrl: String, token: () -> String?): KajakServerApi {
        serverCache?.let { if (it.first == baseUrl) return it.second }
        val authClient = client.newBuilder()
            .addInterceptor { chain ->
                val value = token()
                val request = if (value == null) {
                    chain.request()
                } else {
                    chain.request().newBuilder().header("Authorization", "Bearer $value").build()
                }
                chain.proceed(request)
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KajakServerApi::class.java)
        serverCache = baseUrl to api
        return api
    }

    val imgw: ImgwApi by lazy { retrofit("https://danepubliczne.imgw.pl/").create(ImgwApi::class.java) }

    val openMeteo: OpenMeteoApi by lazy {
        retrofit("https://api.open-meteo.com/").create(OpenMeteoApi::class.java)
    }
}

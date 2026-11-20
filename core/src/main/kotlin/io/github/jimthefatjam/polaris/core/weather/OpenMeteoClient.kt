package io.github.jimthefatjam.polaris.core.weather

import io.github.jimthefatjam.polaris.core.model.DataQuality
import io.github.jimthefatjam.polaris.core.model.WeatherForecast
import io.github.jimthefatjam.polaris.core.model.WeatherSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Abstraction so the repository can be tested and the provider swapped later. */
interface WeatherProvider {
    val name: String
    /** Returns the raw provider payload; parse with [parse]. Kept separate so the raw JSON can be cached. */
    suspend fun fetchRaw(latitude: Double, longitude: Double): String
    fun parse(raw: String, fetchedAt: Instant): WeatherForecast
}

data class Place(
    val name: String,
    val region: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val timeZoneId: String?,
) {
    val displayName: String get() = listOfNotNull(name, region, country).distinct().joinToString(", ")
}

interface PlaceSearch {
    suspend fun search(query: String): List<Place>
}

internal val polarisJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/**
 * Open-Meteo client (https://open-meteo.com). No API key required for non-commercial use.
 * Data are numerical weather model output (best-match model for the location), not observations.
 */
class OpenMeteoClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val forecastBase: HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl(),
    private val geocodingBase: HttpUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl(),
) : WeatherProvider, PlaceSearch {

    override val name = "Open-Meteo"

    override suspend fun fetchRaw(latitude: Double, longitude: Double): String {
        val url = forecastBase.newBuilder()
            .addQueryParameter("latitude", "%.4f".format(Locale.US, latitude))
            .addQueryParameter("longitude", "%.4f".format(Locale.US, longitude))
            .addQueryParameter("current", CURRENT_VARS.joinToString(","))
            .addQueryParameter("hourly", HOURLY_VARS.joinToString(","))
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("timeformat", "unixtime")
            .addQueryParameter("forecast_days", "3")
            .addQueryParameter("wind_speed_unit", "kmh")
            .build()
        return get(url)
    }

    override fun parse(raw: String, fetchedAt: Instant): WeatherForecast = try {
        val r = polarisJson.decodeFromString(OpenMeteoResponse.serializer(), raw)
        WeatherForecast(
            latitude = r.latitude,
            longitude = r.longitude,
            timeZoneId = r.timezone,
            utcOffsetSeconds = r.utcOffsetSeconds,
            current = r.current?.let { c ->
                WeatherSample(
                    time = Instant.ofEpochSecond(c.time),
                    temperatureC = c.temperature, relativeHumidityPct = c.humidity, dewPointC = c.dewPoint,
                    cloudCoverPct = c.cloudCover, cloudCoverLowPct = c.cloudCoverLow,
                    cloudCoverMidPct = c.cloudCoverMid, cloudCoverHighPct = c.cloudCoverHigh,
                    windSpeedKmh = c.windSpeed, windGustsKmh = c.windGusts,
                    precipitationMm = c.precipitation, visibilityM = c.visibility, weatherCode = c.weatherCode,
                    quality = DataQuality.MODEL_CURRENT,
                )
            },
            hourly = r.hourly?.let(::hourlySamples).orEmpty(),
            fetchedAt = fetchedAt,
            provider = name,
        )
    } catch (e: WeatherException) {
        throw e
    } catch (e: Exception) {
        throw WeatherException.Parse(e)
    }

    private fun hourlySamples(h: OpenMeteoHourly): List<WeatherSample> = h.time.indices.map { i ->
        fun List<Double?>?.at() = this?.getOrNull(i)
        WeatherSample(
            time = Instant.ofEpochSecond(h.time[i]),
            temperatureC = h.temperature.at(), relativeHumidityPct = h.humidity.at(), dewPointC = h.dewPoint.at(),
            cloudCoverPct = h.cloudCover.at(), cloudCoverLowPct = h.cloudCoverLow.at(),
            cloudCoverMidPct = h.cloudCoverMid.at(), cloudCoverHighPct = h.cloudCoverHigh.at(),
            windSpeedKmh = h.windSpeed.at(), windGustsKmh = h.windGusts.at(), jetStreamWindKmh = h.windSpeed250.at(),
            precipitationProbabilityPct = h.precipitationProbability.at(), precipitationMm = h.precipitation.at(),
            visibilityM = h.visibility.at(), weatherCode = h.weatherCode?.getOrNull(i),
            quality = DataQuality.FORECAST,
        )
    }

    override suspend fun search(query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        val url = geocodingBase.newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "10")
            .addQueryParameter("language", Locale.getDefault().language.ifBlank { "en" })
            .addQueryParameter("format", "json")
            .build()
        val raw = get(url)
        val resp = try {
            polarisJson.decodeFromString(GeocodingResponse.serializer(), raw)
        } catch (e: Exception) {
            throw WeatherException.Parse(e)
        }
        return resp.results.orEmpty().map {
            Place(it.name, it.admin1, it.country, it.latitude, it.longitude, it.elevation, it.timezone)
        }
    }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> body
                    resp.code == 429 -> throw WeatherException.RateLimited(
                        resp.header("Retry-After")?.toLongOrNull(), reasonOf(body),
                    )
                    else -> throw WeatherException.Http(resp.code, reasonOf(body))
                }
            }
        } catch (e: IOException) {
            throw WeatherException.Network(e)
        }
    }

    private fun reasonOf(body: String): String? =
        runCatching { polarisJson.decodeFromString(OpenMeteoError.serializer(), body).reason }.getOrNull()

    companion object {
        const val USER_AGENT = "Polaris-Android/0.1.0"

        val CURRENT_VARS = listOf(
            "temperature_2m", "relative_humidity_2m", "dew_point_2m", "cloud_cover", "cloud_cover_low",
            "cloud_cover_mid", "cloud_cover_high", "wind_speed_10m", "wind_gusts_10m", "precipitation",
            "weather_code", "visibility",
        )
        val HOURLY_VARS = listOf(
            "temperature_2m", "relative_humidity_2m", "dew_point_2m", "cloud_cover", "cloud_cover_low",
            "cloud_cover_mid", "cloud_cover_high", "wind_speed_10m", "wind_gusts_10m", "wind_speed_250hPa",
            "precipitation_probability", "precipitation", "visibility", "weather_code",
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

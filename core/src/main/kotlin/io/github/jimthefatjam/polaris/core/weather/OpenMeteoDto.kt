package io.github.jimthefatjam.polaris.core.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire format of https://api.open-meteo.com/v1/forecast with `timeformat=unixtime`. */
@Serializable
internal data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int? = null,
    val elevation: Double? = null,
    val current: OpenMeteoCurrent? = null,
    val hourly: OpenMeteoHourly? = null,
)

@Serializable
internal data class OpenMeteoCurrent(
    val time: Long,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Double? = null,
    @SerialName("dew_point_2m") val dewPoint: Double? = null,
    @SerialName("cloud_cover") val cloudCover: Double? = null,
    @SerialName("cloud_cover_low") val cloudCoverLow: Double? = null,
    @SerialName("cloud_cover_mid") val cloudCoverMid: Double? = null,
    @SerialName("cloud_cover_high") val cloudCoverHigh: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    @SerialName("wind_gusts_10m") val windGusts: Double? = null,
    val precipitation: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    val visibility: Double? = null,
)

@Serializable
internal data class OpenMeteoHourly(
    val time: List<Long>,
    @SerialName("temperature_2m") val temperature: List<Double?>? = null,
    @SerialName("relative_humidity_2m") val humidity: List<Double?>? = null,
    @SerialName("dew_point_2m") val dewPoint: List<Double?>? = null,
    @SerialName("cloud_cover") val cloudCover: List<Double?>? = null,
    @SerialName("cloud_cover_low") val cloudCoverLow: List<Double?>? = null,
    @SerialName("cloud_cover_mid") val cloudCoverMid: List<Double?>? = null,
    @SerialName("cloud_cover_high") val cloudCoverHigh: List<Double?>? = null,
    @SerialName("wind_speed_10m") val windSpeed: List<Double?>? = null,
    @SerialName("wind_gusts_10m") val windGusts: List<Double?>? = null,
    @SerialName("wind_speed_250hPa") val windSpeed250: List<Double?>? = null,
    @SerialName("precipitation_probability") val precipitationProbability: List<Double?>? = null,
    val precipitation: List<Double?>? = null,
    val visibility: List<Double?>? = null,
    @SerialName("weather_code") val weatherCode: List<Int?>? = null,
)

@Serializable
internal data class OpenMeteoError(val error: Boolean = true, val reason: String? = null)

@Serializable
internal data class GeocodingResponse(val results: List<GeocodingResult>? = null)

@Serializable
internal data class GeocodingResult(
    val id: Long? = null,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val timezone: String? = null,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val admin1: String? = null,
)

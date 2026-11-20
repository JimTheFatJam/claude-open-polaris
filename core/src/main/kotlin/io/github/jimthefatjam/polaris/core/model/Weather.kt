package io.github.jimthefatjam.polaris.core.model

import java.time.Instant

/**
 * Weather values for one instant (current conditions or one forecast hour).
 * Every field is nullable: `null` means the provider did not supply it, never "ideal".
 * Units are SI-ish and fixed: °C, %, km/h, mm, metres. Conversion happens only in the UI.
 */
data class WeatherSample(
    val time: Instant,
    val temperatureC: Double? = null,
    val relativeHumidityPct: Double? = null,
    val dewPointC: Double? = null,
    val cloudCoverPct: Double? = null,
    val cloudCoverLowPct: Double? = null,
    val cloudCoverMidPct: Double? = null,
    val cloudCoverHighPct: Double? = null,
    val windSpeedKmh: Double? = null,
    val windGustsKmh: Double? = null,
    /** Wind speed at the 250 hPa level (~10 km, jet-stream altitude). Used only as a seeing proxy. */
    val jetStreamWindKmh: Double? = null,
    val precipitationProbabilityPct: Double? = null,
    val precipitationMm: Double? = null,
    val visibilityM: Double? = null,
    val weatherCode: Int? = null,
    val quality: DataQuality = DataQuality.FORECAST,
) {
    /** Dew-point depression (temperature minus dew point), the key driver of dew on optics. */
    val dewPointSpreadC: Double?
        get() = if (temperatureC != null && dewPointC != null) temperatureC - dewPointC else null
}

data class WeatherForecast(
    val latitude: Double,
    val longitude: Double,
    val timeZoneId: String?,
    val utcOffsetSeconds: Int?,
    val current: WeatherSample?,
    val hourly: List<WeatherSample>,
    val fetchedAt: Instant,
    val provider: String = "Open-Meteo",
) {
    /** The hourly sample whose hour contains [instant], if any. */
    fun hourAt(instant: Instant): WeatherSample? =
        hourly.lastOrNull { !it.time.isAfter(instant) && it.time.plusSeconds(3600).isAfter(instant) }
}

/** WMO weather interpretation codes as used by Open-Meteo. */
object WmoWeather {
    fun describe(code: Int?): String? = when (code) {
        null -> null
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown ($code)"
    }

    /** True for codes that mean precipitation or fog at the surface. */
    fun isWet(code: Int?): Boolean = code != null && (code in 45..99)
}

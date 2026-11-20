package io.github.jimthefatjam.polaris.core.weather

/** Typed failures so the UI can explain what went wrong instead of showing a generic error. */
sealed class WeatherException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : WeatherException("Network unavailable: ${cause.message}", cause)
    class RateLimited(val retryAfterSeconds: Long?, reason: String?) :
        WeatherException("Weather service rate limit reached${reason?.let { ": $it" } ?: ""}")
    class Http(val code: Int, reason: String?) : WeatherException("Weather service error $code${reason?.let { ": $it" } ?: ""}")
    class Parse(cause: Throwable) : WeatherException("Unexpected weather data format", cause)
}

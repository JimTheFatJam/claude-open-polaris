package io.github.jimthefatjam.polaris.core.weather

import io.github.jimthefatjam.polaris.core.model.WeatherForecast
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale

enum class WeatherSource { NETWORK, CACHE_FRESH, CACHE_STALE }

data class WeatherResult(
    val forecast: WeatherForecast?,
    val source: WeatherSource?,
    /** Set when a refresh failed; the result may still carry cached data. */
    val error: WeatherException?,
) {
    val isStale: Boolean get() = source == WeatherSource.CACHE_STALE
}

/**
 * Cache-first weather repository.
 *  - Cached data younger than [freshFor] are returned without a network call (unless forced).
 *  - Forced refreshes are throttled to one per [minForcedInterval] to respect the free API.
 *  - After HTTP 429 the repository backs off (Retry-After, else [rateLimitBackoff]) and serves cache.
 *  - On any failure, cached data up to [maxStaleAge] old are returned alongside the error.
 */
class WeatherRepository(
    private val provider: WeatherProvider,
    private val cache: WeatherCache,
    private val clock: Clock = Clock.systemUTC(),
    private val freshFor: Duration = Duration.ofMinutes(30),
    private val minForcedInterval: Duration = Duration.ofSeconds(60),
    private val rateLimitBackoff: Duration = Duration.ofMinutes(15),
    private val maxStaleAge: Duration = Duration.ofHours(36),
) {
    private val mutex = Mutex()
    private var backoffUntil: Instant = Instant.EPOCH

    suspend fun get(latitude: Double, longitude: Double, force: Boolean = false): WeatherResult = mutex.withLock {
        val key = cacheKey(latitude, longitude)
        val now = clock.instant()
        val cached = cache.read(key)?.let { c -> runCatching { provider.parse(c.raw, c.fetchedAt) }.getOrNull() }
        val age = cached?.let { Duration.between(it.fetchedAt, now) }

        if (cached != null && age != null) {
            val fresh = age < freshFor
            val throttled = age < minForcedInterval
            if ((fresh && !force) || throttled) return WeatherResult(cached, WeatherSource.CACHE_FRESH, null)
        }
        if (now.isBefore(backoffUntil)) {
            return fallback(cached, age, WeatherException.RateLimited(Duration.between(now, backoffUntil).seconds, "backing off"))
        }

        return try {
            val raw = provider.fetchRaw(latitude, longitude)
            val parsed = provider.parse(raw, now)
            cache.write(key, CachedPayload(raw, now))
            WeatherResult(parsed, WeatherSource.NETWORK, null)
        } catch (e: WeatherException) {
            if (e is WeatherException.RateLimited) {
                backoffUntil = now.plus(e.retryAfterSeconds?.let { Duration.ofSeconds(it) } ?: rateLimitBackoff)
            }
            fallback(cached, age, e)
        }
    }

    private fun fallback(cached: WeatherForecast?, age: Duration?, e: WeatherException): WeatherResult =
        if (cached != null && age != null && age <= maxStaleAge) {
            WeatherResult(cached, if (age < freshFor) WeatherSource.CACHE_FRESH else WeatherSource.CACHE_STALE, e)
        } else WeatherResult(null, null, e)

    companion object {
        /** ~1 km grid; finer location changes don't change the forecast meaningfully. */
        fun cacheKey(lat: Double, lon: Double) = "om_%.2f_%.2f".format(Locale.US, lat, lon).replace('-', 'm')
    }
}

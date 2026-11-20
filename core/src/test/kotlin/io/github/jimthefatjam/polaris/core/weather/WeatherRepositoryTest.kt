package io.github.jimthefatjam.polaris.core.weather

import io.github.jimthefatjam.polaris.core.model.WeatherForecast
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WeatherRepositoryTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private class FakeProvider : WeatherProvider {
        override val name = "fake"
        var calls = 0
        var failWith: WeatherException? = null
        override suspend fun fetchRaw(latitude: Double, longitude: Double): String {
            calls++
            failWith?.let { throw it }
            return """{"latitude":$latitude,"longitude":$longitude}"""
        }
        override fun parse(raw: String, fetchedAt: Instant): WeatherForecast =
            OpenMeteoClient().parse(raw, fetchedAt)
    }

    private val start = Instant.parse("2025-01-10T20:00:00Z")

    @Test fun firstLoadHitsNetworkThenServesFreshCache() = runTest {
        val clock = MutableClock(start)
        val p = FakeProvider()
        val repo = WeatherRepository(p, InMemoryWeatherCache(), clock)
        assertEquals(WeatherSource.NETWORK, repo.get(1.0, 2.0).source)
        clock.now = start.plus(Duration.ofMinutes(10))
        val second = repo.get(1.0, 2.0)
        assertEquals(WeatherSource.CACHE_FRESH, second.source)
        assertEquals(1, p.calls)
    }

    @Test fun forcedRefreshIsThrottled() = runTest {
        val clock = MutableClock(start)
        val p = FakeProvider()
        val repo = WeatherRepository(p, InMemoryWeatherCache(), clock)
        repo.get(1.0, 2.0)
        clock.now = start.plusSeconds(20)
        repo.get(1.0, 2.0, force = true)
        assertEquals(1, p.calls)
        clock.now = start.plusSeconds(90)
        assertEquals(WeatherSource.NETWORK, repo.get(1.0, 2.0, force = true).source)
        assertEquals(2, p.calls)
    }

    @Test fun networkFailureFallsBackToStaleCacheWithError() = runTest {
        val clock = MutableClock(start)
        val p = FakeProvider()
        val repo = WeatherRepository(p, InMemoryWeatherCache(), clock)
        repo.get(1.0, 2.0)
        clock.now = start.plus(Duration.ofHours(2))
        p.failWith = WeatherException.Network(IOException("offline"))
        val r = repo.get(1.0, 2.0)
        assertNotNull(r.forecast)
        assertTrue(r.isStale)
        assertTrue(r.error is WeatherException.Network)
    }

    @Test fun veryOldCacheIsNotServed() = runTest {
        val clock = MutableClock(start)
        val p = FakeProvider()
        val repo = WeatherRepository(p, InMemoryWeatherCache(), clock)
        repo.get(1.0, 2.0)
        clock.now = start.plus(Duration.ofDays(3))
        p.failWith = WeatherException.Network(IOException("offline"))
        val r = repo.get(1.0, 2.0)
        assertNull(r.forecast)
        assertNotNull(r.error)
    }

    @Test fun noCacheAndNoNetworkReturnsError() = runTest {
        val p = FakeProvider().apply { failWith = WeatherException.Network(IOException("offline")) }
        val r = WeatherRepository(p, InMemoryWeatherCache(), MutableClock(start)).get(1.0, 2.0)
        assertNull(r.forecast)
        assertNull(r.source)
        assertTrue(r.error is WeatherException.Network)
    }

    @Test fun rateLimitTriggersBackoff() = runTest {
        val clock = MutableClock(start)
        val p = FakeProvider().apply { failWith = WeatherException.RateLimited(null, "limit") }
        val repo = WeatherRepository(p, InMemoryWeatherCache(), clock, rateLimitBackoff = Duration.ofMinutes(15))
        assertTrue(repo.get(1.0, 2.0).error is WeatherException.RateLimited)
        p.failWith = null
        clock.now = start.plus(Duration.ofMinutes(5))
        assertTrue(repo.get(1.0, 2.0, force = true).error is WeatherException.RateLimited)
        assertEquals(1, p.calls) // no request during back-off
        clock.now = start.plus(Duration.ofMinutes(16))
        assertEquals(WeatherSource.NETWORK, repo.get(1.0, 2.0).source)
    }

    @Test fun fileCacheRoundTripsAndSurvivesNewRepository() = runTest {
        val dir = Files.createTempDirectory("polaris-cache").toFile()
        val clock = MutableClock(start)
        val p = FakeProvider()
        WeatherRepository(p, FileWeatherCache(dir), clock).get(10.0, 20.0)
        clock.now = start.plus(Duration.ofMinutes(5))
        val r = WeatherRepository(p, FileWeatherCache(dir), clock).get(10.0, 20.0)
        assertEquals(WeatherSource.CACHE_FRESH, r.source)
        assertEquals(1, p.calls)
        dir.deleteRecursively()
    }

    @Test fun cacheKeyRoundsAndHandlesNegatives() {
        assertEquals(WeatherRepository.cacheKey(40.71284, -74.00601), WeatherRepository.cacheKey(40.7099, -74.0081))
        assertTrue(WeatherRepository.cacheKey(-33.9, 18.4).startsWith("om_m33.90"))
    }
}

package io.github.jimthefatjam.polaris.core.weather

import io.github.jimthefatjam.polaris.core.model.DataQuality
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

class OpenMeteoClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenMeteoClient
    private val sample = javaClass.classLoader!!.getResource("open_meteo_sample.json")!!.readText()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OpenMeteoClient(forecastBase = server.url("/v1/forecast"), geocodingBase = server.url("/v1/search"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun parsesCurrentAndHourly() {
        val f = client.parse(sample, Instant.EPOCH)
        assertEquals("America/New_York", f.timeZoneId)
        assertEquals(-18000, f.utcOffsetSeconds)
        val cur = f.current!!
        assertEquals(DataQuality.MODEL_CURRENT, cur.quality)
        assertEquals(12.0, cur.cloudCoverPct!!, 0.0)
        assertEquals(6.3, cur.dewPointSpreadC!!, 1e-9)
        assertEquals(4, f.hourly.size)
        assertEquals(118.0, f.hourly[1].jetStreamWindKmh!!, 0.0)
        assertEquals(DataQuality.FORECAST, f.hourly[0].quality)
    }

    @Test fun nullValuesStayNull() {
        val last = client.parse(sample, Instant.EPOCH).hourly[3]
        assertNull(last.temperatureC)
        assertNull(last.cloudCoverPct)
        assertNull(last.weatherCode)
        assertNull(last.dewPointSpreadC)
        assertNotNull(last.windSpeedKmh)
    }

    @Test fun hourLookup() {
        val f = client.parse(sample, Instant.EPOCH)
        assertEquals(Instant.ofEpochSecond(1736550000), f.hourAt(Instant.ofEpochSecond(1736550000 + 1800))!!.time)
        assertNull(f.hourAt(Instant.ofEpochSecond(1736546400 - 1)))
        assertNull(f.hourAt(Instant.ofEpochSecond(1736557200 + 3600)))
    }

    @Test fun missingSectionsAreTolerated() {
        val f = client.parse("""{"latitude":1.0,"longitude":2.0}""", Instant.EPOCH)
        assertNull(f.current)
        assertTrue(f.hourly.isEmpty())
    }

    @Test fun malformedJsonIsParseError() {
        try { client.parse("{not json", Instant.EPOCH); fail() } catch (e: WeatherException.Parse) { /* expected */ }
    }

    @Test fun requestIncludesExpectedParameters() = runTest {
        server.enqueue(MockResponse().setBody(sample))
        client.fetchRaw(40.7128, -74.006)
        val url = server.takeRequest().requestUrl!!
        assertEquals("40.7128", url.queryParameter("latitude"))
        assertEquals("-74.0060", url.queryParameter("longitude"))
        assertEquals("auto", url.queryParameter("timezone"))
        assertTrue(url.queryParameter("hourly")!!.contains("wind_speed_250hPa"))
        assertTrue(url.queryParameter("hourly")!!.contains("cloud_cover_high"))
    }

    @Test fun http429BecomesRateLimited() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
            .setBody("""{"error":true,"reason":"Daily API request limit exceeded."}"""))
        try {
            client.fetchRaw(1.0, 2.0); fail()
        } catch (e: WeatherException.RateLimited) {
            assertEquals(120L, e.retryAfterSeconds)
            assertTrue(e.message!!.contains("limit"))
        }
    }

    @Test fun http400BecomesHttpErrorWithReason() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":true,"reason":"Latitude must be in range"}"""))
        try {
            client.fetchRaw(1.0, 2.0); fail()
        } catch (e: WeatherException.Http) {
            assertEquals(400, e.code)
            assertTrue(e.message!!.contains("Latitude"))
        }
    }

    @Test fun networkFailureBecomesNetworkError() = runTest {
        server.shutdown()
        try { client.fetchRaw(1.0, 2.0); fail() } catch (e: WeatherException.Network) { /* expected */ }
    }

    @Test fun geocodingParsesResults() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"results":[{"id":1,"name":"Manila","latitude":14.6042,"longitude":120.9822,"elevation":13.0,
               "timezone":"Asia/Manila","country":"Philippines","admin1":"National Capital Region"}]}"""))
        val r = client.search("Manila")
        assertEquals(1, r.size)
        assertEquals("Asia/Manila", r[0].timeZoneId)
        assertEquals("Manila, National Capital Region, Philippines", r[0].displayName)
    }

    @Test fun geocodingWithNoResults() = runTest {
        server.enqueue(MockResponse().setBody("""{"generationtime_ms":0.1}"""))
        assertTrue(client.search("zzzz").isEmpty())
        assertTrue(client.search("  ").isEmpty())
    }
}

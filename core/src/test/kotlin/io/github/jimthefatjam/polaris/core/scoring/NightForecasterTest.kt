package io.github.jimthefatjam.polaris.core.scoring

import io.github.jimthefatjam.polaris.core.model.DataQuality
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.WeatherForecast
import io.github.jimthefatjam.polaris.core.model.WeatherSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class NightForecasterTest {
    private val loc = GeoLocation(40.7128, -74.0060)
    private val forecaster = NightForecaster()

    private fun forecast(from: Instant, hours: Int, cloudAt: (Int) -> Double) = WeatherForecast(
        loc.latitude, loc.longitude, "America/New_York", -18000,
        current = WeatherSample(from, 0.0, 50.0, -8.0, cloudAt(0), 0.0, 0.0, 0.0, 5.0, 8.0, null, 0.0, 0.0, 30000.0, 0,
            DataQuality.MODEL_CURRENT),
        hourly = (0 until hours).map { h ->
            WeatherSample(from.plus(Duration.ofHours(h.toLong())), 0.0, 50.0, -8.0, cloudAt(h), cloudAt(h), 0.0, 0.0,
                5.0, 8.0, 60.0, 0.0, 0.0, 30000.0, 0)
        },
        fetchedAt = from,
    )

    @Test fun hourlyOutlookRunsUntilSunrise() {
        val now = Instant.parse("2025-01-10T23:10:00Z") // 18:10 EST, after sunset
        val f = forecast(Instant.parse("2025-01-10T20:00:00Z"), 30) { 10.0 }
        val r = forecaster.build(loc, f, 4, now)
        assertTrue(r.hourly.isNotEmpty())
        assertEquals(Instant.parse("2025-01-10T23:00:00Z"), r.hourly.first().time)
        val sunrise = r.night.sunrise!!
        assertTrue(r.hourly.last().time.isBefore(sunrise))
        assertTrue(r.hourly.all { it.weather != null })
    }

    @Test fun daytimeOutlookStartsAroundSunset() {
        val now = Instant.parse("2025-01-10T15:00:00Z") // 10:00 EST
        val r = forecaster.build(loc, forecast(Instant.parse("2025-01-10T12:00:00Z"), 40) { 0.0 }, 4, now)
        val sunset = r.night.sunset!!
        assertTrue(r.hourly.first().time.isBefore(sunset))
        assertTrue(Duration.between(r.hourly.first().time, sunset) <= Duration.ofHours(2))
    }

    @Test fun bestWindowFindsTheClearSpell() {
        val start = Instant.parse("2025-01-11T00:00:00Z")
        // Overcast except 05:00–08:00 UTC (00–03 EST).
        val f = forecast(start, 24) { h -> if (h in 5..7) 0.0 else 100.0 }
        val r = forecaster.build(loc, f, 3, start)
        val w = r.bestWindows[ObservationType.DEEP_SKY]
        assertNotNull(w)
        assertEquals(Instant.parse("2025-01-11T05:00:00Z"), w!!.start)
        assertEquals(Instant.parse("2025-01-11T08:00:00Z"), w.end)
    }

    @Test fun noWindowWhenAllOvercast() {
        val start = Instant.parse("2025-01-11T00:00:00Z")
        val r = forecaster.build(loc, forecast(start, 24) { 100.0 }, 3, start)
        assertNull(r.bestWindows[ObservationType.DEEP_SKY])
    }

    @Test fun withoutForecastScoresAreUnavailableButSkyStillComputed() {
        val r = forecaster.build(loc, null, 3, Instant.parse("2025-01-11T03:00:00Z"))
        assertNull(r.headline.score)
        assertTrue(r.hourly.isNotEmpty())
        assertTrue(r.hourly.all { it.score(ObservationType.GENERAL) == null })
    }

    @Test fun staleCurrentBlockFallsBackToHourly() {
        val start = Instant.parse("2025-01-11T00:00:00Z")
        val f = forecast(start, 24) { h -> h * 4.0 }
        val now = start.plus(Duration.ofHours(5)).plusSeconds(600)
        val w = forecaster.currentWeather(f, now)!!
        assertEquals(20.0, w.cloudCoverPct!!, 0.0)
        assertEquals(DataQuality.FORECAST, w.quality)
    }
}

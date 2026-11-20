package io.github.jimthefatjam.polaris.core.scoring

import io.github.jimthefatjam.polaris.core.model.DataQuality
import io.github.jimthefatjam.polaris.core.model.MoonInfo
import io.github.jimthefatjam.polaris.core.model.RiseSet
import io.github.jimthefatjam.polaris.core.model.SkySnapshot
import io.github.jimthefatjam.polaris.core.model.TwilightPhase
import io.github.jimthefatjam.polaris.core.model.WeatherSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScoringEngineTest {
    private val engine = ScoringEngine()
    private val t = Instant.parse("2025-01-10T23:00:00Z")

    private fun sky(
        sunAlt: Double = -30.0,
        moonAlt: Double = -20.0,
        moonIllum: Double = 0.0,
        planets: Map<String, Double> = mapOf("Jupiter" to 50.0, "Saturn" to -10.0),
    ) = SkySnapshot(
        t, sunAlt, TwilightPhase.fromSunAltitude(sunAlt),
        MoonInfo(moonAlt, 180.0, moonIllum, if (moonIllum > 0.5) 180.0 else 90.0, "Test", RiseSet(null, null, null), null, null),
        planets,
    )

    private fun clearWeather(
        cloud: Double = 0.0, low: Double? = 0.0, mid: Double? = 0.0, high: Double? = 0.0,
        rh: Double = 45.0, temp: Double = 10.0, dew: Double = -2.0, wind: Double = 5.0, gust: Double = 8.0,
        jet: Double? = 40.0, pop: Double? = 0.0, precip: Double? = 0.0, vis: Double? = 30000.0, code: Int? = 0,
    ) = WeatherSample(
        time = t, temperatureC = temp, relativeHumidityPct = rh, dewPointC = dew,
        cloudCoverPct = cloud, cloudCoverLowPct = low, cloudCoverMidPct = mid, cloudCoverHighPct = high,
        windSpeedKmh = wind, windGustsKmh = gust, jetStreamWindKmh = jet,
        precipitationProbabilityPct = pop, precipitationMm = precip, visibilityM = vis, weatherCode = code,
    )

    private fun score(type: ObservationType, w: WeatherSample?, s: SkySnapshot = sky(), bortle: Int? = 2) =
        engine.assess(type, ObservingInputs(w, s, bortle))

    @Test fun perfectDarkClearNightIsExcellentForDeepSky() {
        val a = score(ObservationType.DEEP_SKY, clearWeather())
        assertTrue("score ${a.score}", a.score!! >= 80)
        assertEquals(Rating.EXCELLENT, a.rating)
        assertEquals(Confidence.HIGH, a.confidence)
    }

    @Test fun overcastIsUnsuitableForEverything() {
        val w = clearWeather(cloud = 100.0, low = 100.0, mid = 100.0, high = 100.0)
        ObservationType.entries.forEach {
            val a = score(it, w, sky(moonAlt = 40.0, moonIllum = 0.5))
            assertTrue("$it ${a.score}", a.score!! <= 5)
            assertEquals(Rating.UNSUITABLE, a.rating)
            assertTrue(a.factors.first { f -> f.id == "cloud" }.impact == Impact.LIMITING)
        }
    }

    @Test fun activePrecipitationZeroesTheScore() {
        val w = clearWeather(precip = 1.2, code = 63, pop = 90.0)
        ObservationType.entries.forEach { assertEquals(0, score(it, w, sky(moonAlt = 40.0, moonIllum = 0.5)).score) }
    }

    @Test fun missingCloudDataGivesNoScoreInsteadOfIdeal() {
        val w = clearWeather().copy(cloudCoverPct = null, cloudCoverLowPct = null, cloudCoverMidPct = null, cloudCoverHighPct = null)
        val a = score(ObservationType.GENERAL, w)
        assertNull(a.score)
        assertNull(a.rating)
        assertTrue(a.summary.contains("unavailable"))
    }

    @Test fun noWeatherAtAllGivesNoScore() {
        assertNull(score(ObservationType.DEEP_SKY, null).score)
    }

    @Test fun unknownBortleIsFlaggedAndExcludedNotAssumedIdeal() {
        val known = score(ObservationType.DEEP_SKY, clearWeather(), bortle = 1)
        val unknown = score(ObservationType.DEEP_SKY, clearWeather(), bortle = null)
        val lp = unknown.factors.first { it.id == "lightPollution" }
        assertNull(lp.value)
        assertEquals(DataQuality.UNAVAILABLE, lp.quality)
        assertNotEquals(Confidence.HIGH, unknown.confidence)
        assertTrue(known.score!! >= unknown.score!! - 1)
    }

    @Test fun lightPollutionLowersDeepSkyMoreThanPlanets() {
        val dark = clearWeather()
        val dsoDrop = score(ObservationType.DEEP_SKY, dark, bortle = 1).score!! - score(ObservationType.DEEP_SKY, dark, bortle = 9).score!!
        val planetDrop = score(ObservationType.PLANETS, dark, bortle = 1).score!! - score(ObservationType.PLANETS, dark, bortle = 9).score!!
        assertTrue(dsoDrop > 15)
        assertEquals(0, planetDrop)
    }

    @Test fun brightMoonHurtsDeepSkyButEnablesLunarObservation() {
        val w = clearWeather()
        val moonless = sky(moonAlt = -30.0, moonIllum = 0.98)
        val fullMoonHigh = sky(moonAlt = 60.0, moonIllum = 0.98)
        val dsoDark = score(ObservationType.DEEP_SKY, w, moonless).score!!
        val dsoMoon = score(ObservationType.DEEP_SKY, w, fullMoonHigh).score!!
        assertTrue("dso dark $dsoDark vs moon $dsoMoon", dsoDark - dsoMoon >= 15)
        assertEquals(0, score(ObservationType.MOON, w, moonless).score)
        assertTrue(score(ObservationType.MOON, w, fullMoonHigh).score!! >= 60)
    }

    @Test fun quarterMoonBeatsFullMoonForLunarDetail() {
        val w = clearWeather()
        val quarter = score(ObservationType.MOON, w, sky(moonAlt = 50.0, moonIllum = 0.5)).score!!
        val full = score(ObservationType.MOON, w, sky(moonAlt = 50.0, moonIllum = 1.0)).score!!
        assertTrue(quarter > full)
    }

    @Test fun moonIsObservableInDaylightButDeepSkyIsNot() {
        val w = clearWeather()
        val day = sky(sunAlt = 30.0, moonAlt = 40.0, moonIllum = 0.5)
        assertEquals(0, score(ObservationType.DEEP_SKY, w, day).score)
        assertEquals(0, score(ObservationType.GENERAL, w, day).score)
        assertTrue(score(ObservationType.MOON, w, day).score!! > 30)
    }

    @Test fun twilightPartiallyAllowsPlanetsButNotDeepSky() {
        val w = clearWeather()
        val civil = sky(sunAlt = -4.0)
        assertTrue(score(ObservationType.PLANETS, w, civil).score!! >= 40)
        assertTrue(score(ObservationType.DEEP_SKY, w, civil).score!! <= 5)
    }

    @Test fun noPlanetsUpMeansPlanetaryScoreZero() {
        val s = sky(planets = mapOf("Jupiter" to -5.0, "Saturn" to -20.0))
        val a = score(ObservationType.PLANETS, clearWeather(), s)
        assertEquals(0, a.score)
        assertTrue(a.summary, a.summary.contains("no bright planets", ignoreCase = true))
    }

    @Test fun jetStreamDegradesPlanetsMoreThanDeepSky() {
        val calm = clearWeather(jet = 40.0)
        val jet = clearWeather(jet = 220.0)
        val planetDrop = score(ObservationType.PLANETS, calm).score!! - score(ObservationType.PLANETS, jet).score!!
        val dsoDrop = score(ObservationType.DEEP_SKY, calm).score!! - score(ObservationType.DEEP_SKY, jet).score!!
        assertTrue("planet $planetDrop dso $dsoDrop", planetDrop > dsoDrop * 3)
    }

    @Test fun missingJetStreamMarksSeeingUnavailable() {
        val a = score(ObservationType.PLANETS, clearWeather(jet = null))
        val seeing = a.factors.first { it.id == "seeing" }
        assertNull(seeing.value)
        assertEquals(Confidence.LOW, a.confidence)
    }

    @Test fun thinHighCloudPenalisesDeepSkyMoreThanPlanets() {
        val cirrus = clearWeather(cloud = 60.0, low = 0.0, mid = 0.0, high = 60.0)
        val planets = score(ObservationType.PLANETS, cirrus).score!!
        val dso = score(ObservationType.DEEP_SKY, cirrus).score!!
        assertTrue("planets $planets dso $dso", planets > dso + 20)
    }

    @Test fun scoreIsMonotonicInCloudCover() {
        ObservationType.entries.forEach { type ->
            var previous = Int.MAX_VALUE
            for (c in 0..100 step 5) {
                val s = score(type, clearWeather(cloud = c.toDouble(), low = c.toDouble(), mid = 0.0, high = 0.0),
                    sky(moonAlt = 40.0, moonIllum = 0.5)).score!!
                assertTrue("$type at $c%: $s > $previous", s <= previous)
                previous = s
            }
        }
    }

    @Test fun highWindAndDewReduceScore() {
        val calm = score(ObservationType.PLANETS, clearWeather()).score!!
        val windy = score(ObservationType.PLANETS, clearWeather(wind = 45.0, gust = 70.0)).score!!
        val dewy = score(ObservationType.PLANETS, clearWeather(temp = 5.0, dew = 4.5, rh = 96.0)).score!!
        assertTrue(windy < calm - 10)
        assertTrue(dewy < calm)
    }

    @Test fun scoresAlwaysWithinBounds() {
        val extremes = listOf(
            clearWeather(), clearWeather(cloud = 100.0), clearWeather(wind = 500.0, gust = 900.0, jet = 900.0),
            clearWeather(rh = 150.0, vis = -10.0, temp = -60.0, dew = 40.0), clearWeather(pop = 300.0),
        )
        extremes.forEach { w ->
            ObservationType.entries.forEach { type ->
                val s = score(type, w, sky(moonAlt = 40.0, moonIllum = 0.5)).score!!
                assertTrue(s in 0..100)
            }
        }
    }

    @Test fun ratingThresholds() {
        assertEquals(Rating.EXCELLENT, Rating.fromScore(100))
        assertEquals(Rating.EXCELLENT, Rating.fromScore(80))
        assertEquals(Rating.GOOD, Rating.fromScore(79))
        assertEquals(Rating.GOOD, Rating.fromScore(60))
        assertEquals(Rating.FAIR, Rating.fromScore(59))
        assertEquals(Rating.FAIR, Rating.fromScore(40))
        assertEquals(Rating.POOR, Rating.fromScore(39))
        assertEquals(Rating.POOR, Rating.fromScore(20))
        assertEquals(Rating.UNSUITABLE, Rating.fromScore(19))
        assertEquals(Rating.UNSUITABLE, Rating.fromScore(0))
    }

    @Test fun weightsSumToOne() {
        ObservationType.entries.forEach {
            assertEquals(it.name, 1.0, engine.weightsFor(it).values.sum(), 1e-9)
        }
    }

    @Test fun piecewiseInterpolation() {
        val knots = listOf(0.0 to 0.0, 10.0 to 1.0, 20.0 to 0.0)
        assertEquals(0.0, ScoringEngine.piecewise(-5.0, knots), 0.0)
        assertEquals(0.5, ScoringEngine.piecewise(5.0, knots), 1e-9)
        assertEquals(1.0, ScoringEngine.piecewise(10.0, knots), 1e-9)
        assertEquals(0.25, ScoringEngine.piecewise(17.5, knots), 1e-9)
        assertEquals(0.0, ScoringEngine.piecewise(99.0, knots), 0.0)
    }

    @Test fun explanationsMentionWhatHelpsAndHurts() {
        val a = score(ObservationType.DEEP_SKY, clearWeather(), sky(moonAlt = 60.0, moonIllum = 0.98))
        assertTrue(a.summary, a.summary.contains("moonlight", ignoreCase = true))
        assertTrue(a.factors.all { it.detail.isNotBlank() })
    }
}

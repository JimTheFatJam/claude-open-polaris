package io.github.jimthefatjam.polaris.core.astro

import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.TwilightPhase
import io.github.jimthefatjam.polaris.core.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

class SkyCalculatorTest {
    private val calc = SkyCalculator()
    private val greenwich = GeoLocation(51.4769, 0.0, 46.0)
    private val newYork = GeoLocation(40.7128, -74.0060, 10.0)
    private val oslo = GeoLocation(59.9139, 10.7522)
    private val tromso = GeoLocation(69.6492, 18.9553)
    private val quito = GeoLocation(-0.1807, -78.4678, 2850.0)

    private fun assertNear(expected: Instant, actual: Instant?, tolerance: Duration, msg: String) {
        assertNotNull(msg, actual)
        val diff = Duration.between(expected, actual).abs()
        assertTrue("$msg: expected $expected got $actual (diff $diff)", diff <= tolerance)
    }

    @Test fun sunAltitudeAtGreenwichSolsticeNoon() {
        // 90 − 51.48 + 23.44 ≈ 61.96° at local apparent noon (~12:02 UTC).
        val alt = calc.sunAltitude(greenwich, Instant.parse("2024-06-21T12:02:00Z"))
        assertEquals(61.96, alt, 0.3)
    }

    @Test fun sunIsBelowHorizonAtLocalMidnight() {
        val alt = calc.sunAltitude(newYork, Instant.parse("2024-12-21T05:00:00Z"))
        assertTrue(alt < -50)
        assertEquals(TwilightPhase.NIGHT, TwilightPhase.fromSunAltitude(alt))
    }

    @Test fun polarisAltitudeApproximatesLatitude() {
        for (hour in listOf(0L, 6L, 12L, 18L)) {
            val t = Instant.parse("2025-03-01T00:00:00Z").plus(Duration.ofHours(hour))
            val p = calc.position(Catalog.byId("polaris")!!, greenwich, t)
            assertEquals("hour $hour", greenwich.latitude, p.altitudeDeg, 1.0)
        }
    }

    @Test fun newYorkSolsticeSunriseAndSunset() {
        // USNO: 2024-06-21 sunrise 05:25 EDT (09:25 UTC), sunset 20:31 EDT (00:31 UTC Jun 22).
        val night = calc.nightTimes(newYork, Instant.parse("2024-06-21T16:00:00Z"))
        assertNear(Instant.parse("2024-06-22T00:31:00Z"), night.sunset, Duration.ofMinutes(3), "sunset")
        assertNear(Instant.parse("2024-06-22T09:25:00Z"), night.sunrise, Duration.ofMinutes(3), "sunrise")
        assertFalse(night.noAstronomicalDarkness)
        // Ordering of twilight boundaries.
        val seq = listOf(night.sunset, night.civilDusk, night.nauticalDusk, night.astronomicalDusk,
            night.astronomicalDawn, night.nauticalDawn, night.civilDawn, night.sunrise)
        seq.forEach { assertNotNull(it) }
        assertEquals(seq, seq.sortedBy { it })
    }

    @Test fun nightTimesDuringNightReferToTheCurrentNight() {
        val during = calc.nightTimes(newYork, Instant.parse("2024-06-22T04:00:00Z"))
        assertNear(Instant.parse("2024-06-22T00:31:00Z"), during.sunset, Duration.ofMinutes(3), "sunset in progress")
        assertNear(Instant.parse("2024-06-22T09:25:00Z"), during.sunrise, Duration.ofMinutes(3), "sunrise")
    }

    @Test fun osloHasNoAstronomicalDarknessAtMidsummer() {
        val night = calc.nightTimes(oslo, Instant.parse("2024-06-21T12:00:00Z"))
        assertNotNull(night.sunset)
        assertTrue(night.noAstronomicalDarkness)
        assertNull(night.astronomicalDusk)
    }

    @Test fun tromsoMidnightSunIsPolarDay() {
        val night = calc.nightTimes(tromso, Instant.parse("2024-06-21T12:00:00Z"))
        assertTrue(night.polarDay)
        assertNull(night.sunset)
    }

    @Test fun tromsoPolarNight() {
        val night = calc.nightTimes(tromso, Instant.parse("2024-12-21T11:00:00Z"))
        assertTrue(night.polarNight)
    }

    @Test fun equatorHasShortTwilight() {
        val night = calc.nightTimes(quito, Instant.parse("2024-03-20T15:00:00Z"))
        val twilight = Duration.between(night.sunset, night.astronomicalDusk)
        assertTrue("equatorial twilight $twilight", twilight < Duration.ofMinutes(80))
    }

    @Test fun fullMoonSearchMatchesKnownDate() {
        // Full Moon: 2024-01-25 17:54 UTC.
        val info = calc.moonInfo(greenwich, Instant.parse("2024-01-20T00:00:00Z"))
        assertNear(Instant.parse("2024-01-25T17:54:00Z"), info.nextFullMoon, Duration.ofMinutes(10), "full moon")
        // New Moon: 2024-02-09 22:59 UTC.
        assertNear(Instant.parse("2024-02-09T22:59:00Z"), info.nextNewMoon, Duration.ofMinutes(10), "new moon")
    }

    @Test fun moonIlluminationAtFullAndNew() {
        val full = calc.moonInfo(greenwich, Instant.parse("2024-01-25T17:54:00Z"))
        assertTrue(full.illuminatedFraction > 0.99)
        assertEquals("Full Moon", full.phaseName)
        val new = calc.moonInfo(greenwich, Instant.parse("2024-02-09T22:59:00Z"))
        assertTrue(new.illuminatedFraction < 0.01)
        assertEquals("New Moon", new.phaseName)
    }

    @Test fun fixedObjectRiseSetIsConsistentWithAltitude() {
        val sirius = Catalog.byId("sirius")!!
        val from = Instant.parse("2025-01-10T12:00:00Z")
        val rs = calc.riseSet(sirius, greenwich, from)
        assertNotNull(rs.rise); assertNotNull(rs.set); assertNotNull(rs.transit)
        assertTrue(rs.rise!!.isAfter(from) && rs.set!!.isAfter(from))
        // At the computed rise time the apparent (refracted) altitude is ≈ 0.
        assertEquals(0.0, calc.position(sirius, greenwich, rs.rise!!).altitudeDeg, 0.3)
        assertEquals(0.0, calc.position(sirius, greenwich, rs.set!!).altitudeDeg, 0.3)
        // At transit the object is due south (az ≈ 180) from the northern hemisphere.
        val atTransit = calc.position(sirius, greenwich, rs.transit!!)
        assertEquals(180.0, atTransit.azimuthDeg, 0.5)
    }

    @Test fun circumpolarAndNeverRisingObjects() {
        val from = Instant.parse("2025-01-10T12:00:00Z")
        assertTrue(calc.riseSet(Catalog.byId("polaris")!!, greenwich, from).alwaysUp)
        assertTrue(calc.riseSet(Catalog.byId("canopus")!!, greenwich, from).neverUp)
        // Canopus from the southern hemisphere at 35°S rises and sets? It is circumpolar south of ~37.3°S.
        val sydney = GeoLocation(-33.87, 151.21)
        val rs = calc.riseSet(Catalog.byId("canopus")!!, sydney, from)
        assertFalse(rs.neverUp)
    }

    @Test fun planetRiseSetFound() {
        val rs = calc.riseSet(Catalog.byId("jupiter")!!, newYork, Instant.parse("2025-01-10T12:00:00Z"))
        assertNotNull(rs.rise); assertNotNull(rs.set); assertNotNull(rs.transit)
    }

    @Test fun angularSeparationBasics() {
        assertEquals(90.0, SkyCalculator.angularSeparation(0.0, 0.0, 0.0, 90.0), 1e-9)
        assertEquals(90.0, SkyCalculator.angularSeparation(0.0, 0.0, 90.0, 0.0), 1e-9)
        assertEquals(0.0, SkyCalculator.angularSeparation(45.0, 359.5, 45.0, 359.5), 1e-9)
        assertEquals(1.0, SkyCalculator.angularSeparation(0.0, 359.5, 0.0, 0.5), 1e-6)
        assertEquals(180.0, SkyCalculator.angularSeparation(0.0, 0.0, 0.0, 180.0), 1e-9)
    }

    @Test fun phaseNames() {
        assertEquals("New Moon", SkyCalculator.phaseName(0.0))
        assertEquals("New Moon", SkyCalculator.phaseName(355.0))
        assertEquals("First Quarter", SkyCalculator.phaseName(90.0))
        assertEquals("Full Moon", SkyCalculator.phaseName(180.0))
        assertEquals("Last Quarter", SkyCalculator.phaseName(270.0))
        assertEquals("Waxing Crescent", SkyCalculator.phaseName(45.0))
        assertEquals("Waning Gibbous", SkyCalculator.phaseName(-150.0))
    }

    @Test fun assessmentFiltersBelowHorizonAndRanksVisible() {
        val t = Instant.parse("2025-01-10T23:00:00Z")
        val sunAlt = calc.sunAltitude(greenwich, t)
        val moon = calc.moonInfo(greenwich, t)
        val canopus = calc.assess(Catalog.byId("canopus")!!, greenwich, t, sunAlt, moon, bortle = 4)
        assertEquals(Visibility.BELOW_HORIZON, canopus.visibility)
        assertEquals(0.0, canopus.observability, 0.0)
        val capella = calc.assess(Catalog.byId("capella")!!, greenwich, t, sunAlt, moon, bortle = 4)
        assertTrue(capella.isAboveHorizon)
        assertTrue(capella.observability > 0)
    }

    @Test fun sunIsNeverRecommended() {
        val t = Instant.parse("2025-06-10T12:00:00Z")
        val sun = calc.assess(Catalog.sun, greenwich, t)
        assertEquals(0.0, sun.observability, 0.0)
        assertTrue(sun.notes.any { it.contains("solar filter") })
    }

    @Test fun deepSkyLostInDaylight() {
        val t = Instant.parse("2025-06-10T12:00:00Z")
        val m13 = calc.assess(Catalog.byId("m13")!!, newYork, t)
        assertTrue(m13.observability == 0.0)
    }

    @Test fun catalogIsWellFormed() {
        val ids = Catalog.all.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        Catalog.all.filter { it.isFixed }.forEach {
            assertTrue(it.name, it.raHoursJ2000!! in 0.0..24.0)
            assertTrue(it.name, it.decDegJ2000!! in -90.0..90.0)
        }
        assertTrue(Catalog.search("andromeda").any { it.id == "m31" })
        assertTrue(Catalog.search("M 42").any { it.id == "m42" })
        assertTrue(Catalog.search("ngc5139").any { it.id == "omega_cen" })
    }

    @Test fun limitingMagnitudeBehaviour() {
        val darkNight = VisibilityModel.limitingMagnitude(-30.0, 1, null, null)
        val city = VisibilityModel.limitingMagnitude(-30.0, 9, null, null)
        val twilight = VisibilityModel.limitingMagnitude(-8.0, 1, null, null)
        val moonlit = VisibilityModel.limitingMagnitude(-30.0, 1, 1.0, 60.0)
        assertTrue(darkNight.nakedEye > city.nakedEye)
        assertTrue(twilight.nakedEye < darkNight.nakedEye)
        assertTrue(moonlit.nakedEye < darkNight.nakedEye)
        assertTrue(abs(darkNight.nakedEye - 7.8) < 1e-9)
    }
}

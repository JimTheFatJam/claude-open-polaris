package io.github.jimthefatjam.polaris.core.astro

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Spherical
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.moonPhase
import io.github.cosinekitty.astronomy.rotationEqjEqd
import io.github.cosinekitty.astronomy.searchAltitude
import io.github.cosinekitty.astronomy.searchHourAngle
import io.github.cosinekitty.astronomy.searchMoonPhase
import io.github.cosinekitty.astronomy.searchRiseSet
import io.github.cosinekitty.astronomy.siderealTime
import io.github.jimthefatjam.polaris.core.model.CelestialObject
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.MoonInfo
import io.github.jimthefatjam.polaris.core.model.NightTimes
import io.github.jimthefatjam.polaris.core.model.ObjectPosition
import io.github.jimthefatjam.polaris.core.model.ObjectType
import io.github.jimthefatjam.polaris.core.model.RiseSet
import io.github.jimthefatjam.polaris.core.model.SkySnapshot
import io.github.jimthefatjam.polaris.core.model.TwilightPhase
import io.github.jimthefatjam.polaris.core.model.Visibility
import java.time.Duration
import java.time.Instant
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thin, stateless wrapper over Astronomy Engine that speaks Polaris model types.
 * All angles are degrees, all instants are UTC [Instant]s.
 */
class SkyCalculator {

    // ---------------------------------------------------------------- time helpers

    internal fun Instant.toAstro(): Time = Time.fromMillisecondsSince1970(toEpochMilli())
    internal fun Time.toInstant(): Instant = Instant.ofEpochMilli(toMillisecondsSince1970())

    private fun observer(loc: GeoLocation) = Observer(loc.latitude, loc.longitude, loc.elevationMeters)

    // ---------------------------------------------------------------- positions

    data class HorizontalCoords(val altitudeDeg: Double, val azimuthDeg: Double, val raHours: Double, val decDeg: Double)

    /** Apparent topocentric position of a solar-system body, including atmospheric refraction. */
    fun bodyPosition(body: Body, loc: GeoLocation, at: Instant): HorizontalCoords {
        val t = at.toAstro()
        val obs = observer(loc)
        val eq = equator(body, t, obs, EquatorEpoch.OfDate, Aberration.Corrected)
        val hor = horizon(t, obs, eq.ra, eq.dec, Refraction.Normal)
        return HorizontalCoords(hor.altitude, hor.azimuth, eq.ra, eq.dec)
    }

    /** Apparent position of a fixed (J2000) object, precessed and nutated to the date. */
    fun fixedPosition(raHoursJ2000: Double, decDegJ2000: Double, loc: GeoLocation, at: Instant): HorizontalCoords {
        val t = at.toAstro()
        val eqj = Spherical(decDegJ2000, raHoursJ2000 * 15.0, 1.0).toVector(t)
        val eqd = rotationEqjEqd(t).rotate(eqj).toEquatorial()
        val hor = horizon(t, observer(loc), eqd.ra, eqd.dec, Refraction.Normal)
        return HorizontalCoords(hor.altitude, hor.azimuth, eqd.ra, eqd.dec)
    }

    fun position(obj: CelestialObject, loc: GeoLocation, at: Instant): HorizontalCoords {
        val body = bodyFor(obj)
        return if (body != null) bodyPosition(body, loc, at)
        else fixedPosition(obj.raHoursJ2000!!, obj.decDegJ2000!!, loc, at)
    }

    /** Apparent motion of an object right now, from a ±30 s central difference. */
    data class Motion(val azimuthDegPerMin: Double, val altitudeDegPerMin: Double, val totalDegPerMin: Double)

    fun motion(obj: CelestialObject, loc: GeoLocation, at: Instant): Motion {
        val a = position(obj, loc, at.minusSeconds(30))
        val b = position(obj, loc, at.plusSeconds(30))
        var dAz = b.azimuthDeg - a.azimuthDeg
        if (dAz > 180) dAz -= 360
        if (dAz < -180) dAz += 360
        val dAlt = b.altitudeDeg - a.altitudeDeg
        val total = angularSeparation(a.altitudeDeg, a.azimuthDeg, b.altitudeDeg, b.azimuthDeg)
        return Motion(dAz, dAlt, total)
    }

    fun sunAltitude(loc: GeoLocation, at: Instant): Double = bodyPosition(Body.Sun, loc, at).altitudeDeg

    // ---------------------------------------------------------------- rise / set / transit

    /**
     * Next rise, next set and next upper transit after [from] (searching up to ~1 day ahead).
     * For fixed objects that never rise or never set, the flags are set and times are null.
     */
    fun riseSet(obj: CelestialObject, loc: GeoLocation, from: Instant): RiseSet {
        val body = bodyFor(obj)
        return if (body != null) bodyRiseSet(body, loc, from) else fixedRiseSet(obj, loc, from)
    }

    private fun bodyRiseSet(body: Body, loc: GeoLocation, from: Instant): RiseSet {
        val obs = observer(loc)
        val t = from.toAstro()
        val rise = searchRiseSet(body, obs, Direction.Rise, t, 1.5)?.toInstant()
        val set = searchRiseSet(body, obs, Direction.Set, t, 1.5)?.toInstant()
        val transit = runCatching { searchHourAngle(body, obs, 0.0, t, +1).time.toInstant() }.getOrNull()
        val up = bodyPosition(body, loc, from).altitudeDeg > 0
        return RiseSet(
            rise = rise,
            transit = transit,
            set = set,
            alwaysUp = rise == null && set == null && up,
            neverUp = rise == null && set == null && !up,
        )
    }

    /**
     * Analytic rise/set for a fixed object: the hour angle H0 at which altitude = h0 satisfies
     * cos H0 = (sin h0 − sin φ sin δ) / (cos φ cos δ), with h0 = −0.5667° for standard refraction.
     */
    internal fun fixedRiseSet(obj: CelestialObject, loc: GeoLocation, from: Instant): RiseSet {
        val now = fixedPosition(obj.raHoursJ2000!!, obj.decDegJ2000!!, loc, from)
        val phi = Math.toRadians(loc.latitude)
        val dec = Math.toRadians(now.decDeg)
        val h0 = Math.toRadians(-0.5667)
        val cosH0 = (sin(h0) - sin(phi) * sin(dec)) / (cos(phi) * cos(dec))

        val t = from.toAstro()
        val lstHours = siderealTime(t) + loc.longitude / 15.0
        val hoursToTransitSidereal = mod24(now.raHours - lstHours)
        val siderealToSolar = 0.9972695663
        val nextTransit = from.plusSeconds((hoursToTransitSidereal * siderealToSolar * 3600).toLong())

        if (cosH0 < -1.0) return RiseSet(null, nextTransit, null, alwaysUp = true)
        if (cosH0 > 1.0) return RiseSet(null, nextTransit, null, neverUp = true)

        val h0Hours = Math.toDegrees(acos(cosH0)) / 15.0 * siderealToSolar
        val siderealDaySec = (24 * 3600 * siderealToSolar).toLong()
        val transits = listOf(nextTransit.minusSeconds(siderealDaySec), nextTransit, nextTransit.plusSeconds(siderealDaySec))
        val halfArc = (h0Hours * 3600).toLong()
        val rise = transits.map { it.minusSeconds(halfArc) }.firstOrNull { it.isAfter(from) }
        val set = transits.map { it.plusSeconds(halfArc) }.firstOrNull { it.isAfter(from) }
        return RiseSet(rise, nextTransit, set)
    }

    private fun mod24(h: Double): Double = h - 24.0 * floor(h / 24.0)

    // ---------------------------------------------------------------- Sun & twilight

    /**
     * Twilight boundaries of the night that is in progress at [at], or of the next night if it is
     * currently daytime. All searches are bounded so polar day/night yields nulls plus flags.
     */
    fun nightTimes(loc: GeoLocation, at: Instant): NightTimes {
        val obs = observer(loc)
        val sunAlt = sunAltitude(loc, at)
        val isNight = sunAlt <= -0.833

        val sunset: Instant? = if (!isNight) {
            searchRiseSet(Body.Sun, obs, Direction.Set, at.toAstro(), 1.0)?.toInstant()
        } else {
            // Most recent sunset before `at`, looking back up to ~1.5 days.
            var cursor = at.minus(Duration.ofHours(36)).toAstro()
            var last: Instant? = null
            while (true) {
                val s = searchRiseSet(Body.Sun, obs, Direction.Set, cursor, 1.6)?.toInstant() ?: break
                if (s.isAfter(at)) break
                last = s
                cursor = s.plusSeconds(60).toAstro()
            }
            last
        }

        if (sunset == null) {
            // Sun does not set (polar day) or has not set within the search window (polar night).
            val polarDay = !isNight
            val rise = if (isNight) searchRiseSet(Body.Sun, obs, Direction.Rise, at.toAstro(), 2.0)?.toInstant() else null
            return NightTimes(
                sunset = null, civilDusk = null, nauticalDusk = null, astronomicalDusk = null,
                astronomicalDawn = null, nauticalDawn = null, civilDawn = null, sunrise = rise,
                noAstronomicalDarkness = polarDay || sunAltitude(loc, at) > -18.0,
                polarDay = polarDay, polarNight = !polarDay,
            )
        }

        val start = sunset.toAstro()
        val sunrise = searchRiseSet(Body.Sun, obs, Direction.Rise, start, 1.0)?.toInstant()
        fun dusk(alt: Double) = searchAltitude(Body.Sun, obs, Direction.Set, start, 1.0, alt)?.toInstant()
            ?.takeIf { sunrise == null || it.isBefore(sunrise) }
        fun dawn(alt: Double, after: Instant?) = after?.let {
            searchAltitude(Body.Sun, obs, Direction.Rise, it.toAstro(), 1.0, alt)?.toInstant()
                ?.takeIf { d -> sunrise == null || !d.isAfter(sunrise.plusSeconds(60)) }
        }

        val civilDusk = dusk(-6.0)
        val nauticalDusk = dusk(-12.0)
        val astroDusk = dusk(-18.0)
        return NightTimes(
            sunset = sunset,
            civilDusk = civilDusk,
            nauticalDusk = nauticalDusk,
            astronomicalDusk = astroDusk,
            astronomicalDawn = dawn(-18.0, astroDusk),
            nauticalDawn = dawn(-12.0, nauticalDusk),
            civilDawn = dawn(-6.0, civilDusk),
            sunrise = sunrise,
            noAstronomicalDarkness = astroDusk == null,
        )
    }

    // ---------------------------------------------------------------- Moon

    fun moonInfo(loc: GeoLocation, at: Instant): MoonInfo {
        val t = at.toAstro()
        val pos = bodyPosition(Body.Moon, loc, at)
        val illum = illumination(Body.Moon, t).phaseFraction
        val phase = moonPhase(t)
        return MoonInfo(
            altitudeDeg = pos.altitudeDeg,
            azimuthDeg = pos.azimuthDeg,
            illuminatedFraction = illum,
            phaseAngleDeg = phase,
            phaseName = phaseName(phase),
            riseSet = bodyRiseSet(Body.Moon, loc, at),
            nextNewMoon = searchMoonPhase(0.0, t, 32.0)?.toInstant(),
            nextFullMoon = searchMoonPhase(180.0, t, 32.0)?.toInstant(),
        )
    }

    // ---------------------------------------------------------------- snapshot for scoring

    fun snapshot(loc: GeoLocation, at: Instant): SkySnapshot {
        val sunAlt = sunAltitude(loc, at)
        val planetAlts = NAKED_EYE_PLANETS.associate { (name, body) -> name to bodyPosition(body, loc, at).altitudeDeg }
        return SkySnapshot(
            time = at,
            sunAltitudeDeg = sunAlt,
            twilight = TwilightPhase.fromSunAltitude(sunAlt),
            moon = moonInfoLight(loc, at),
            planetAltitudes = planetAlts,
        )
    }

    /** Moon info without the (relatively expensive) event searches; used for hourly scoring. */
    private fun moonInfoLight(loc: GeoLocation, at: Instant): MoonInfo {
        val t = at.toAstro()
        val pos = bodyPosition(Body.Moon, loc, at)
        val phase = moonPhase(t)
        return MoonInfo(
            pos.altitudeDeg, pos.azimuthDeg, illumination(Body.Moon, t).phaseFraction, phase, phaseName(phase),
            RiseSet(null, null, null), null, null,
        )
    }

    // ---------------------------------------------------------------- objects

    /**
     * Full position + visibility assessment for [obj].
     * @param bortle user-provided Bortle class or null if unknown.
     * @param cloudCoverPct current cloud cover, used only to rank objects (null = not considered).
     */
    fun assess(
        obj: CelestialObject,
        loc: GeoLocation,
        at: Instant,
        sunAltDeg: Double = sunAltitude(loc, at),
        moon: MoonInfo? = null,
        bortle: Int? = null,
        cloudCoverPct: Double? = null,
        includeRiseSet: Boolean = true,
    ): ObjectPosition {
        val pos = position(obj, loc, at)
        val body = bodyFor(obj)
        val t = at.toAstro()
        val illumInfo = if (body != null && body != Body.Sun) runCatching { illumination(body, t) }.getOrNull() else null
        val mag = when {
            obj.type == ObjectType.SUN -> obj.magnitude
            illumInfo != null -> illumInfo.mag
            else -> obj.magnitude
        }
        val moonSep = if (obj.type != ObjectType.MOON && moon != null) {
            angularSeparation(pos.altitudeDeg, pos.azimuthDeg, moon.altitudeDeg, moon.azimuthDeg)
        } else null

        val limits = VisibilityModel.limitingMagnitude(sunAltDeg, bortle, moon?.illuminatedFraction, moon?.altitudeDeg)
        val eval = VisibilityModel.evaluate(obj, mag, pos.altitudeDeg, sunAltDeg, limits, moon, moonSep, cloudCoverPct)

        return ObjectPosition(
            obj = obj,
            time = at,
            altitudeDeg = pos.altitudeDeg,
            azimuthDeg = pos.azimuthDeg,
            raHours = pos.raHours,
            decDeg = pos.decDeg,
            magnitude = mag,
            riseSet = if (includeRiseSet) riseSet(obj, loc, at) else null,
            visibility = eval.visibility,
            illuminatedFraction = illumInfo?.phaseFraction,
            moonSeparationDeg = moonSep,
            observability = eval.score,
            notes = eval.notes,
        )
    }

    companion object {
        val NAKED_EYE_PLANETS: List<Pair<String, Body>> = listOf(
            "Mercury" to Body.Mercury, "Venus" to Body.Venus, "Mars" to Body.Mars,
            "Jupiter" to Body.Jupiter, "Saturn" to Body.Saturn,
        )

        fun bodyFor(obj: CelestialObject): Body? = when (obj.id) {
            "sun" -> Body.Sun
            "moon" -> Body.Moon
            "mercury" -> Body.Mercury
            "venus" -> Body.Venus
            "mars" -> Body.Mars
            "jupiter" -> Body.Jupiter
            "saturn" -> Body.Saturn
            "uranus" -> Body.Uranus
            "neptune" -> Body.Neptune
            else -> null
        }

        fun phaseName(phaseAngle: Double): String = when (((phaseAngle % 360) + 360) % 360) {
            in 0.0..<11.25 -> "New Moon"
            in 11.25..<78.75 -> "Waxing Crescent"
            in 78.75..<101.25 -> "First Quarter"
            in 101.25..<168.75 -> "Waxing Gibbous"
            in 168.75..<191.25 -> "Full Moon"
            in 191.25..<258.75 -> "Waning Gibbous"
            in 258.75..<281.25 -> "Last Quarter"
            in 281.25..<348.75 -> "Waning Crescent"
            else -> "New Moon"
        }

        /** Great-circle separation between two horizontal positions (haversine form, stable for small angles). */
        fun angularSeparation(alt1: Double, az1: Double, alt2: Double, az2: Double): Double {
            val a1 = Math.toRadians(alt1)
            val a2 = Math.toRadians(alt2)
            val dAlt = a2 - a1
            val dAz = Math.toRadians(az2 - az1)
            val h = sin(dAlt / 2).let { it * it } + cos(a1) * cos(a2) * sin(dAz / 2).let { it * it }
            return Math.toDegrees(2 * asin(sqrt(h.coerceIn(0.0, 1.0))))
        }
    }
}

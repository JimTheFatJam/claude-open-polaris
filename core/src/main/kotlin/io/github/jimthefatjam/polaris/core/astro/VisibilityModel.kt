package io.github.jimthefatjam.polaris.core.astro

import io.github.jimthefatjam.polaris.core.model.CelestialObject
import io.github.jimthefatjam.polaris.core.model.MoonInfo
import io.github.jimthefatjam.polaris.core.model.ObjectType
import io.github.jimthefatjam.polaris.core.model.Visibility
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Heuristic visibility model used to rank objects. It is intentionally simple and documented in
 * docs/STARGAZING_SCORING.md ("Object ranking"). It is NOT a photometric sky-brightness model.
 */
object VisibilityModel {

    /** Typical naked-eye limiting magnitude (NELM) at the zenith per Bortle class (Bortle 2001, midpoint of range). */
    private val NELM_BY_BORTLE = mapOf(1 to 7.8, 2 to 7.3, 3 to 6.8, 4 to 6.3, 5 to 5.8, 6 to 5.3, 7 to 4.8, 8 to 4.3, 9 to 4.0)

    /** Assumed NELM when the user has not provided a Bortle class (typical suburban sky). */
    const val UNKNOWN_SKY_NELM = 5.5

    /** Extra reach of a small (≈100 mm) telescope over the naked eye for point sources. */
    const val SMALL_TELESCOPE_GAIN_MAG = 4.5

    data class Limits(val nakedEye: Double, val telescope: Double)

    fun nelmForBortle(bortle: Int?): Double = bortle?.let { NELM_BY_BORTLE[it.coerceIn(1, 9)] } ?: UNKNOWN_SKY_NELM

    /**
     * Estimated limiting magnitudes given the Sun's altitude, the sky's Bortle class and moonlight.
     * Twilight values follow the commonly quoted "first stars at civil dusk ≈ mag 1–2, nautical ≈ 4".
     */
    fun limitingMagnitude(sunAltDeg: Double, bortle: Int?, moonIllum: Double?, moonAltDeg: Double?): Limits {
        val dark = nelmForBortle(bortle)
        val twilightCap = when {
            sunAltDeg > -0.833 -> -4.0               // daylight: only the Moon, Venus (and Jupiter with optics)
            sunAltDeg > -6.0 -> lerp(-4.0, 1.5, (-0.833 - sunAltDeg) / 5.167)
            sunAltDeg > -12.0 -> lerp(1.5, 4.0, (-6.0 - sunAltDeg) / 6.0)
            sunAltDeg > -18.0 -> lerp(4.0, dark, (-12.0 - sunAltDeg) / 6.0)
            else -> dark
        }
        // Moonlight brightens the whole sky; up to ~1.5–2 mag loss under a high full Moon at a dark site.
        val moonLoss = if (moonIllum != null && moonAltDeg != null && moonAltDeg > 0) {
            val darkSiteSensitivity = ((dark - 4.0) / 3.8).coerceIn(0.2, 1.0) // light-polluted skies lose less in relative terms
            1.8 * moonIllum * sqrt(sin(Math.toRadians(moonAltDeg))) * darkSiteSensitivity
        } else 0.0
        val naked = min(twilightCap, dark - moonLoss)
        return Limits(naked, naked + SMALL_TELESCOPE_GAIN_MAG)
    }

    data class Evaluation(val visibility: Visibility, val score: Double, val notes: List<String>)

    fun evaluate(
        obj: CelestialObject,
        magnitude: Double?,
        altitudeDeg: Double,
        sunAltDeg: Double,
        limits: Limits,
        moon: MoonInfo?,
        moonSeparationDeg: Double?,
        cloudCoverPct: Double?,
    ): Evaluation {
        val notes = mutableListOf<String>()
        if (obj.type == ObjectType.SUN) {
            notes += "Never point optics at the Sun without a certified solar filter."
            val vis = if (altitudeDeg > 0) Visibility.WELL_PLACED else Visibility.BELOW_HORIZON
            return Evaluation(vis, 0.0, notes) // never "recommended"
        }
        if (altitudeDeg <= 0.0) return Evaluation(Visibility.BELOW_HORIZON, 0.0, notes)

        val altFactor = altitudeFactor(altitudeDeg)
        if (altitudeDeg < 15) notes += "Low altitude: more atmosphere, more blur and extinction."

        val effMag = effectiveMagnitude(obj, magnitude)
        val limit = if (obj.type.isDeepSky || (magnitude ?: 0.0) > limits.nakedEye) limits.telescope else limits.nakedEye
        val margin = if (effMag == null) 1.0 else ((limit - effMag) / 2.0).coerceIn(0.0, 1.0)

        var moonFactor = 1.0
        if (obj.type.isDeepSky && moon != null && moon.altitudeDeg > 0 && moonSeparationDeg != null) {
            if (moon.illuminatedFraction > 0.3 && moonSeparationDeg < 30) {
                moonFactor = 0.4 + 0.6 * (moonSeparationDeg / 30.0)
                notes += "Within ${moonSeparationDeg.toInt()}° of a bright Moon."
            } else if (moon.illuminatedFraction > 0.6) {
                moonFactor = 0.75
                notes += "Bright moonlight washes out faint detail."
            }
        }
        val cloudFactor = cloudCoverPct?.let { 1.0 - (it / 100.0).coerceIn(0.0, 1.0) } ?: 1.0

        val daytime = sunAltDeg > -0.833
        val visibility = when {
            daytime && obj.type != ObjectType.MOON && (magnitude ?: 99.0) > -3.0 -> Visibility.DAYTIME
            margin <= 0.0 -> Visibility.LOST_IN_TWILIGHT
            altitudeDeg < 15 -> Visibility.LOW
            else -> Visibility.WELL_PLACED
        }
        if (obj.type == ObjectType.MOON || obj.type == ObjectType.PLANET) {
            if (moon != null && obj.type == ObjectType.MOON) notes += "${moon.phaseName}, ${(moon.illuminatedFraction * 100).toInt()}% lit."
        }
        val score = if (visibility == Visibility.DAYTIME && obj.type != ObjectType.MOON) 0.0
        else altFactor * margin * moonFactor * cloudFactor * typeAppeal(obj)
        return Evaluation(visibility, score.coerceIn(0.0, 1.0), notes)
    }

    /** 0..1: rises steeply from the horizon and saturates above ~40° (airmass ≈ 1.6). */
    fun altitudeFactor(altDeg: Double): Double = when {
        altDeg <= 0 -> 0.0
        altDeg < 5 -> 0.1 * altDeg / 5.0
        else -> min(1.0, 0.1 + 0.9 * (altDeg - 5) / 35.0)
    }

    /**
     * Extended objects spread their light: approximate the loss of contrast by adding
     * 0.8 mag per doubling of size beyond 5′ (a crude surface-brightness correction).
     */
    fun effectiveMagnitude(obj: CelestialObject, magnitude: Double?): Double? {
        magnitude ?: return null
        val size = obj.sizeArcmin ?: return magnitude
        if (!obj.type.isDeepSky || obj.type == ObjectType.OPEN_CLUSTER) return magnitude
        return magnitude + 0.8 * max(0.0, log2(size / 5.0))
    }

    /** Small bias so that showpiece objects outrank equally placed field stars. */
    private fun typeAppeal(obj: CelestialObject): Double = when (obj.type) {
        ObjectType.MOON, ObjectType.PLANET -> 1.0
        ObjectType.STAR -> 0.7
        ObjectType.DOUBLE_STAR -> 0.85
        else -> 0.9
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f.coerceIn(0.0, 1.0)
}

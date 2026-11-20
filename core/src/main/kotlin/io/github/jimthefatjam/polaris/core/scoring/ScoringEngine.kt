package io.github.jimthefatjam.polaris.core.scoring

import io.github.jimthefatjam.polaris.core.astro.VisibilityModel
import io.github.jimthefatjam.polaris.core.model.DataQuality
import io.github.jimthefatjam.polaris.core.model.SkySnapshot
import io.github.jimthefatjam.polaris.core.model.WeatherSample
import io.github.jimthefatjam.polaris.core.model.WmoWeather
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Inputs for one instant.
 * @property bortle user-provided Bortle class 1..9, or null if unknown.
 */
data class ObservingInputs(
    val weather: WeatherSample?,
    val sky: SkySnapshot,
    val bortle: Int?,
)

/**
 * Stargazing scoring engine. See docs/STARGAZING_SCORING.md for the full rationale.
 *
 *     score = 100 × Π(gates) × Σ(wᵢ·sᵢ) / Σ(wᵢ)       (sum over factors with available data)
 *
 * Gates model show-stoppers (cloud, precipitation, daylight, target below horizon) that a weighted
 * average would wrongly dilute: 100 % overcast must give ~0 no matter how calm the air is.
 * Weighted factors model quality once the sky is usable.
 */
class ScoringEngine {

    fun assessAll(inputs: ObservingInputs): Map<ObservationType, Assessment> =
        ObservationType.entries.associateWith { assess(it, inputs) }

    fun assess(type: ObservationType, inputs: ObservingInputs): Assessment {
        val w = inputs.weather
        val sky = inputs.sky
        val weatherQuality = w?.quality ?: DataQuality.UNAVAILABLE

        val gates = buildList {
            add(cloudGate(type, w, weatherQuality))
            add(precipitationGate(w, weatherQuality))
            add(darknessGate(type, sky))
            targetGate(type, sky)?.let { add(it) }
        }
        val weighted = weightedFactors(type, inputs, weatherQuality)
        val all = gates + weighted

        val cloud = gates.first()
        if (cloud.value == null) {
            return Assessment(
                type, null, null, all,
                "Cloud cover data is unavailable, so conditions can't be assessed.",
                Confidence.LOW,
            )
        }

        val gateProduct = gates.fold(1.0) { acc, f -> acc * (f.value ?: 1.0) }
        val available = weighted.filter { it.value != null }
        val totalWeight = weighted.sumOf { it.weight }
        val availWeight = available.sumOf { it.weight }
        val quality = if (availWeight > 0) available.sumOf { it.weight * it.value!! } / availWeight else 0.5
        val score = (100.0 * gateProduct * quality).roundToInt().coerceIn(0, 100)

        val coverage = if (totalWeight > 0) availWeight / totalWeight else 0.0
        val confidence = when {
            coverage >= 0.85 && gates.all { it.value != null } -> Confidence.HIGH
            coverage >= 0.6 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val rating = Rating.fromScore(score)
        return Assessment(type, score, rating, all, summarize(type, rating, all), confidence)
    }

    // ------------------------------------------------------------------ gates

    internal fun cloudGate(type: ObservationType, w: WeatherSample?, q: DataQuality): Factor {
        val total = w?.cloudCoverPct
        val low = w?.cloudCoverLowPct
        val mid = w?.cloudCoverMidPct
        val high = w?.cloudCoverHighPct
        val bright = type == ObservationType.MOON || type == ObservationType.PLANETS
        // Bright targets shine through thin, high cirrus, so high cloud counts half for them.
        val effective: Double? = if (bright && low != null && mid != null && high != null) {
            maxOf(low, mid, 0.5 * high)
        } else total
        if (effective == null) {
            return Factor("cloud", "Cloud cover", FactorKind.GATE, null, 0.0, DataQuality.UNAVAILABLE, "No cloud data")
        }
        val c = (effective / 100.0).coerceIn(0.0, 1.0)
        val k = when (type) {
            ObservationType.DEEP_SKY -> 1.5
            ObservationType.GENERAL -> 1.2
            ObservationType.PLANETS -> 1.0
            ObservationType.MOON -> 0.8
        }
        val value = (1.0 - c).pow(k)
        val layers = if (low != null && mid != null && high != null) {
            " (low ${low.roundToInt()}%, mid ${mid.roundToInt()}%, high ${high.roundToInt()}%)"
        } else ""
        val detail = when {
            c <= 0.1 -> "Clear skies: ${total?.roundToInt() ?: effective.roundToInt()}% cloud$layers"
            c <= 0.4 -> "Partly cloudy: ${total?.roundToInt() ?: effective.roundToInt()}% cloud$layers"
            c <= 0.75 -> "Mostly cloudy: ${total?.roundToInt() ?: effective.roundToInt()}% cloud$layers"
            else -> "Overcast: ${total?.roundToInt() ?: effective.roundToInt()}% cloud$layers"
        } + if (bright && high != null && high > 30 && effective < (total ?: 0.0)) ". Thin high cloud is less harmful for bright targets" else ""
        return Factor("cloud", "Cloud cover", FactorKind.GATE, value, 0.0, q, detail)
    }

    internal fun precipitationGate(w: WeatherSample?, q: DataQuality): Factor {
        val amount = w?.precipitationMm
        val prob = w?.precipitationProbabilityPct
        val code = w?.weatherCode
        if (amount == null && prob == null && code == null) {
            return Factor("precip", "Precipitation", FactorKind.GATE, null, 0.0, DataQuality.UNAVAILABLE,
                "No precipitation data")
        }
        var value = 1.0
        var detail = "No precipitation expected"
        if (prob != null) {
            value = 1.0 - 0.7 * (prob / 100.0).coerceIn(0.0, 1.0).pow(2)
            detail = "${prob.roundToInt()}% chance of precipitation"
        }
        if (WmoWeather.isWet(code)) {
            value = minOf(value, if (code in 45..48) 0.1 else 0.05)
            detail = "${WmoWeather.describe(code)} reported by the model"
        }
        if (amount != null && amount >= 0.1) {
            value = 0.0
            detail = "Precipitation ${"%.1f".format(amount)} mm/h — keep optics covered"
        }
        return Factor("precip", "Precipitation", FactorKind.GATE, value, 0.0, q, detail)
    }

    internal fun darknessGate(type: ObservationType, sky: SkySnapshot): Factor {
        val a = sky.sunAltitudeDeg
        val curve = when (type) {
            ObservationType.DEEP_SKY -> listOf(-18.0 to 1.0, -12.0 to 0.35, -6.0 to 0.05, -0.833 to 0.0)
            ObservationType.GENERAL -> listOf(-18.0 to 1.0, -12.0 to 0.8, -6.0 to 0.3, -0.833 to 0.0)
            ObservationType.PLANETS -> listOf(-12.0 to 1.0, -6.0 to 0.85, -3.0 to 0.5, -0.833 to 0.1, 5.0 to 0.0)
            ObservationType.MOON -> listOf(-6.0 to 1.0, -0.833 to 0.85, 10.0 to 0.6, 90.0 to 0.55)
        }
        val value = piecewise(a, curve)
        val detail = "${sky.twilight.label} (Sun ${"%.1f".format(a)}°)"
        return Factor("darkness", "Sky darkness", FactorKind.GATE, value, 0.0, DataQuality.CALCULATED, detail)
    }

    internal fun targetGate(type: ObservationType, sky: SkySnapshot): Factor? = when (type) {
        ObservationType.MOON -> {
            val alt = sky.moon.altitudeDeg
            val illum = sky.moon.illuminatedFraction
            val (value, detail) = when {
                alt <= 0 -> 0.0 to "The Moon is below the horizon"
                illum < 0.03 -> 0.1 to "Moon is too close to new to observe"
                else -> 1.0 to "The Moon is up (${alt.roundToInt()}°)"
            }
            Factor("target", "Moon above horizon", FactorKind.GATE, value, 0.0, DataQuality.CALCULATED, detail)
        }
        ObservationType.PLANETS -> {
            val best = sky.planetAltitudes.maxByOrNull { it.value }
            val (value, detail) = if (best == null || best.value <= 0) {
                0.0 to "No bright planets above the horizon"
            } else {
                val up = sky.planetAltitudes.filter { it.value > 0 }.keys.joinToString()
                1.0 to "Up now: $up"
            }
            Factor("target", "Planets above horizon", FactorKind.GATE, value, 0.0, DataQuality.CALCULATED, detail)
        }
        else -> null
    }

    // ------------------------------------------------------------------ weighted factors

    /** Factor weights per observation type. Each row sums to 1.0. Rationale in docs/STARGAZING_SCORING.md. */
    internal fun weightsFor(type: ObservationType): Map<String, Double> = when (type) {
        ObservationType.GENERAL -> mapOf("transparency" to 0.30, "lightPollution" to 0.25, "moonlight" to 0.25,
            "seeing" to 0.05, "wind" to 0.05, "dew" to 0.10)
        ObservationType.MOON -> mapOf("seeing" to 0.35, "altitude" to 0.25, "phase" to 0.15, "wind" to 0.15,
            "transparency" to 0.05, "dew" to 0.05)
        ObservationType.PLANETS -> mapOf("seeing" to 0.45, "altitude" to 0.25, "wind" to 0.15,
            "dew" to 0.10, "transparency" to 0.05)
        ObservationType.DEEP_SKY -> mapOf("transparency" to 0.30, "moonlight" to 0.25, "lightPollution" to 0.25,
            "wind" to 0.10, "dew" to 0.05, "seeing" to 0.05)
    }

    private fun weightedFactors(type: ObservationType, inputs: ObservingInputs, q: DataQuality): List<Factor> {
        val weights = weightsFor(type)
        return weights.map { (id, weight) ->
            when (id) {
                "transparency" -> transparency(inputs.weather, weight)
                "seeing" -> seeing(inputs.weather, weight)
                "wind" -> wind(inputs.weather, weight, q)
                "dew" -> dew(inputs.weather, weight, q)
                "moonlight" -> moonlight(type, inputs.sky, weight)
                "lightPollution" -> lightPollution(type, inputs.bortle, weight)
                "altitude" -> targetAltitude(type, inputs.sky, weight)
                "phase" -> lunarPhase(inputs.sky, weight)
                else -> error("unknown factor $id")
            }
        }.sortedByDescending { it.weight }
    }

    /** Transparency proxy from relative humidity and model visibility. Labelled ESTIMATED. */
    internal fun transparency(w: WeatherSample?, weight: Double): Factor {
        val rh = w?.relativeHumidityPct
        val visKm = w?.visibilityM?.div(1000.0)
        val hum = rh?.let { piecewise(it, listOf(50.0 to 1.0, 70.0 to 0.85, 85.0 to 0.6, 95.0 to 0.3, 100.0 to 0.2)) }
        val vis = visKm?.let { piecewise(it, listOf(1.0 to 0.0, 5.0 to 0.4, 10.0 to 0.7, 24.0 to 1.0)) }
        val value = when {
            hum != null && vis != null -> 0.6 * hum + 0.4 * vis
            hum != null -> hum
            vis != null -> vis
            else -> null
        }
        val parts = listOfNotNull(rh?.let { "humidity ${it.roundToInt()}%" }, visKm?.let { "visibility ${"%.0f".format(it)} km" })
        val detail = if (value == null) "No humidity or visibility data" else {
            val verdict = when {
                value >= 0.8 -> "Likely transparent"
                value >= 0.55 -> "Some haze likely"
                else -> "Hazy / poor transparency likely"
            }
            "$verdict (${parts.joinToString()})"
        }
        return Factor("transparency", "Transparency (est.)", FactorKind.WEIGHTED, value, weight,
            if (value == null) DataQuality.UNAVAILABLE else DataQuality.ESTIMATED, detail)
    }

    /**
     * Seeing proxy from jet-stream (250 hPa) wind speed. Strong upper-level winds correlate with
     * high-altitude turbulence (Bely 1987; Vernin & Muñoz-Tuñón). This is an ESTIMATE, not a measurement.
     */
    internal fun seeing(w: WeatherSample?, weight: Double): Factor {
        val jet = w?.jetStreamWindKmh
        if (jet == null) {
            return Factor("seeing", "Seeing (est.)", FactorKind.WEIGHTED, null, weight, DataQuality.UNAVAILABLE,
                "Upper-air wind unavailable; seeing not estimated")
        }
        var value = piecewise(jet, listOf(70.0 to 1.0, 110.0 to 0.75, 150.0 to 0.5, 200.0 to 0.25, 250.0 to 0.1))
        // Strong surface gusts add ground-layer turbulence.
        val gust = w.windGustsKmh
        if (gust != null && gust > 35) value *= piecewise(gust, listOf(35.0 to 1.0, 60.0 to 0.7))
        val verdict = when {
            value >= 0.8 -> "Steady air likely"
            value >= 0.5 -> "Moderate turbulence likely"
            else -> "Turbulent air likely"
        }
        return Factor("seeing", "Seeing (est.)", FactorKind.WEIGHTED, value, weight, DataQuality.ESTIMATED,
            "$verdict (jet-stream wind ${jet.roundToInt()} km/h)")
    }

    internal fun wind(w: WeatherSample?, weight: Double, q: DataQuality): Factor {
        val speed = w?.windSpeedKmh
        val gust = w?.windGustsKmh
        if (speed == null && gust == null) {
            return Factor("wind", "Wind", FactorKind.WEIGHTED, null, weight, DataQuality.UNAVAILABLE, "No wind data")
        }
        val eff = max(speed ?: 0.0, 0.7 * (gust ?: 0.0))
        val value = piecewise(eff, listOf(10.0 to 1.0, 20.0 to 0.8, 30.0 to 0.55, 40.0 to 0.3, 55.0 to 0.1, 70.0 to 0.0))
        val g = gust?.let { ", gusts ${it.roundToInt()}" } ?: ""
        val verdict = when {
            value >= 0.8 -> "Calm enough for a steady view"
            value >= 0.5 -> "Breezy: some telescope shake at high power"
            else -> "Windy: expect vibration"
        }
        return Factor("wind", "Wind", FactorKind.WEIGHTED, value, weight, q,
            "$verdict (${speed?.roundToInt() ?: "?"} km/h$g)")
    }

    internal fun dew(w: WeatherSample?, weight: Double, q: DataQuality): Factor {
        val spread = w?.dewPointSpreadC
        if (spread == null) {
            return Factor("dew", "Dew risk", FactorKind.WEIGHTED, null, weight, DataQuality.UNAVAILABLE,
                "No temperature/dew-point data")
        }
        val value = piecewise(spread, listOf(0.0 to 0.3, 2.0 to 0.5, 4.0 to 0.8, 6.0 to 1.0))
        val detail = when {
            spread < 2 -> "High dew risk: dew point within ${"%.1f".format(spread)}°C — use a dew heater/shield"
            spread < 5 -> "Moderate dew risk (spread ${"%.1f".format(spread)}°C)"
            else -> "Low dew risk (spread ${"%.1f".format(spread)}°C)"
        }
        return Factor("dew", "Dew risk", FactorKind.WEIGHTED, value, weight, q, detail)
    }

    /** Moonlight penalty: brightness ∝ illuminated fraction × √sin(altitude). Rationale: Krisciunas & Schaefer (1991). */
    internal fun moonlight(type: ObservationType, sky: SkySnapshot, weight: Double): Factor {
        val m = sky.moon
        val brightness = if (m.altitudeDeg <= 0) 0.0 else m.illuminatedFraction * sqrt(sin(Math.toRadians(m.altitudeDeg)))
        val strength = if (type == ObservationType.DEEP_SKY) 0.9 else 0.6
        val value = 1.0 - strength * brightness
        val pct = (m.illuminatedFraction * 100).roundToInt()
        val detail = when {
            m.altitudeDeg <= 0 -> "Moon below the horizon — no moonlight ($pct% lit)"
            brightness < 0.15 -> "Little moonlight (${m.phaseName}, $pct% lit)"
            brightness < 0.5 -> "Moderate moonlight (${m.phaseName}, $pct% lit, ${m.altitudeDeg.roundToInt()}° up)"
            else -> "Bright moonlight (${m.phaseName}, $pct% lit, ${m.altitudeDeg.roundToInt()}° up)"
        }
        return Factor("moonlight", "Moonlight", FactorKind.WEIGHTED, value, weight, DataQuality.CALCULATED, detail)
    }

    internal fun lightPollution(type: ObservationType, bortle: Int?, weight: Double): Factor {
        if (bortle == null) {
            return Factor("lightPollution", "Light pollution", FactorKind.WEIGHTED, null, weight,
                DataQuality.UNAVAILABLE, "Bortle class not set — choose it in Settings for a better estimate")
        }
        val b = bortle.coerceIn(1, 9)
        val slope = if (type == ObservationType.DEEP_SKY) 0.85 else 0.7
        val value = 1.0 - (b - 1) / 8.0 * slope
        return Factor("lightPollution", "Light pollution", FactorKind.WEIGHTED, value, weight,
            DataQuality.USER_PROVIDED, "Bortle $b — ${Bortle.name(b)} (NELM ≈ ${"%.1f".format(VisibilityModel.nelmForBortle(b))})")
    }

    internal fun targetAltitude(type: ObservationType, sky: SkySnapshot, weight: Double): Factor {
        val (name, alt) = if (type == ObservationType.MOON) "Moon" to sky.moon.altitudeDeg
        else sky.planetAltitudes.maxByOrNull { it.value }?.toPair() ?: ("Planets" to -90.0)
        val value = VisibilityModel.altitudeFactor(alt)
        val detail = when {
            alt <= 0 -> "$name below the horizon"
            alt < 20 -> "$name low (${alt.roundToInt()}°): thick, turbulent air near the horizon"
            alt < 40 -> "$name at ${alt.roundToInt()}°"
            else -> "$name high (${alt.roundToInt()}°): steadier view"
        }
        return Factor("altitude", "Target altitude", FactorKind.WEIGHTED, value, weight, DataQuality.CALCULATED, detail)
    }

    /** Lunar detail is best near the terminator; a full Moon looks flat and glaring. */
    internal fun lunarPhase(sky: SkySnapshot, weight: Double): Factor {
        val f = sky.moon.illuminatedFraction
        val value = piecewise(f, listOf(0.03 to 0.5, 0.25 to 1.0, 0.75 to 1.0, 0.97 to 0.65))
        val detail = when {
            f >= 0.97 -> "Full Moon: little shadow relief"
            f in 0.25..0.75 -> "${sky.moon.phaseName}: long shadows along the terminator"
            else -> "${sky.moon.phaseName} (${(f * 100).roundToInt()}% lit)"
        }
        return Factor("phase", "Lunar phase", FactorKind.WEIGHTED, value, weight, DataQuality.CALCULATED, detail)
    }

    // ------------------------------------------------------------------ explanation

    private fun summarize(type: ObservationType, rating: Rating, factors: List<Factor>): String {
        val limiting = factors.filter { it.impact == Impact.LIMITING }.minByOrNull { it.value ?: 1.0 }
        if (limiting != null) return "${rating.label}: ${limiting.detail.replaceFirstChar { it.lowercase() }}."
        val worst = factors.filter { it.kind == FactorKind.WEIGHTED && it.impact == Impact.HURTS }
            .maxByOrNull { it.weight * (1 - (it.value ?: 1.0)) }
        val best = factors.filter { it.kind == FactorKind.WEIGHTED && it.impact == Impact.HELPS }
            .maxByOrNull { it.weight * (it.value ?: 0.0) }
        val pieces = buildList {
            best?.let { add(it.detail.substringBefore(" (").replaceFirstChar { c -> c.lowercase() }) }
            worst?.let { add("but " + it.detail.substringBefore(" (").replaceFirstChar { c -> c.lowercase() }) }
        }
        return if (pieces.isEmpty()) "${rating.label} for ${type.shortLabel.lowercase()}."
        else "${rating.label} for ${type.shortLabel.lowercase()}: ${pieces.joinToString(", ")}."
    }

    companion object {
        /** Linear interpolation through (x, y) knots; clamps outside the range. Knots must be sorted by x. */
        fun piecewise(x: Double, knots: List<Pair<Double, Double>>): Double {
            if (x <= knots.first().first) return knots.first().second
            if (x >= knots.last().first) return knots.last().second
            for (i in 0 until knots.size - 1) {
                val (x0, y0) = knots[i]
                val (x1, y1) = knots[i + 1]
                if (x in x0..x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
            return knots.last().second
        }
    }
}

object Bortle {
    private val names = mapOf(
        1 to "Excellent dark-sky site", 2 to "Typical truly dark site", 3 to "Rural sky",
        4 to "Rural/suburban transition", 5 to "Suburban sky", 6 to "Bright suburban sky",
        7 to "Suburban/urban transition", 8 to "City sky", 9 to "Inner-city sky",
    )
    private val hints = mapOf(
        1 to "Zodiacal light and gegenschein obvious; M33 naked-eye.",
        2 to "Summer Milky Way highly structured; airglow faintly visible.",
        3 to "Some light domes on the horizon; Milky Way still complex.",
        4 to "Light domes in several directions; Milky Way lacks detail near the horizon.",
        5 to "Milky Way washed out near the horizon, faint overhead.",
        6 to "Milky Way only visible near the zenith; sky glows grey.",
        7 to "Whole sky has a grey-white hue; Milky Way invisible.",
        8 to "Sky glows orange/grey; only bright constellations are recognisable.",
        9 to "Only the Moon, planets and a few dozen stars are visible.",
    )
    fun name(b: Int): String = names[b] ?: "Unknown"
    fun hint(b: Int): String = hints[b] ?: ""
}

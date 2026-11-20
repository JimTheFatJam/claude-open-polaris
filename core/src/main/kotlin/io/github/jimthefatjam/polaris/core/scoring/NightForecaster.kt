package io.github.jimthefatjam.polaris.core.scoring

import io.github.jimthefatjam.polaris.core.astro.SkyCalculator
import io.github.jimthefatjam.polaris.core.model.DataQuality
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.NightTimes
import io.github.jimthefatjam.polaris.core.model.SkySnapshot
import io.github.jimthefatjam.polaris.core.model.WeatherForecast
import io.github.jimthefatjam.polaris.core.model.WeatherSample
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class HourlyOutlook(
    val time: Instant,
    val weather: WeatherSample?,
    val sky: SkySnapshot,
    val assessments: Map<ObservationType, Assessment>,
) {
    fun score(type: ObservationType): Int? = assessments[type]?.score
}

data class BestWindow(val type: ObservationType, val start: Instant, val end: Instant, val peakScore: Int)

data class ConditionsReport(
    val location: GeoLocation,
    val generatedAt: Instant,
    val now: Map<ObservationType, Assessment>,
    val nowSky: SkySnapshot,
    val nowWeather: WeatherSample?,
    val night: NightTimes,
    val hourly: List<HourlyOutlook>,
    val bestWindows: Map<ObservationType, BestWindow?>,
) {
    /** The single most useful verdict for "should I take the telescope out right now?". */
    val headline: Assessment get() = now.getValue(ObservationType.GENERAL)
    val bestNow: Assessment?
        get() = now.values.filter { it.score != null }.maxByOrNull { it.score!! }
}

/**
 * Combines a weather forecast with sky geometry into current and hourly assessments for the
 * coming night. Pure function of its inputs (plus Astronomy Engine), so it is easy to test.
 */
class NightForecaster(
    private val sky: SkyCalculator = SkyCalculator(),
    private val engine: ScoringEngine = ScoringEngine(),
) {

    fun build(location: GeoLocation, forecast: WeatherForecast?, bortle: Int?, now: Instant): ConditionsReport {
        val nowSky = sky.snapshot(location, now)
        val nowWeather = forecast?.let { currentWeather(it, now) }
        val nowAssessments = engine.assessAll(ObservingInputs(nowWeather, nowSky, bortle))

        val night = sky.nightTimes(location, now)
        val hours = nightHours(now, night)
        val hourly = hours.map { hourStart ->
            val mid = hourStart.plus(Duration.ofMinutes(30))
            val snap = sky.snapshot(location, mid)
            val w = forecast?.hourAt(hourStart)
            HourlyOutlook(hourStart, w, snap, engine.assessAll(ObservingInputs(w, snap, bortle)))
        }
        val windows = ObservationType.entries.associateWith { bestWindow(it, hourly) }
        return ConditionsReport(location, now, nowAssessments, nowSky, nowWeather, night, hourly, windows)
    }

    /**
     * Prefer the API's "current" block when it is recent (< 90 min old), otherwise fall back to the
     * hourly forecast for this hour. "Current" values are model output, not station measurements.
     */
    internal fun currentWeather(forecast: WeatherForecast, now: Instant): WeatherSample? {
        val cur = forecast.current
        val hour = forecast.hourAt(now)
        if (cur != null && Duration.between(cur.time, now).abs() <= Duration.ofMinutes(90)) {
            // Fill fields the current block lacks (e.g. precipitation probability, jet-stream wind) from the hour.
            return cur.copy(
                precipitationProbabilityPct = cur.precipitationProbabilityPct ?: hour?.precipitationProbabilityPct,
                jetStreamWindKmh = cur.jetStreamWindKmh ?: hour?.jetStreamWindKmh,
                visibilityM = cur.visibilityM ?: hour?.visibilityM,
                cloudCoverLowPct = cur.cloudCoverLowPct ?: hour?.cloudCoverLowPct,
                cloudCoverMidPct = cur.cloudCoverMidPct ?: hour?.cloudCoverMidPct,
                cloudCoverHighPct = cur.cloudCoverHighPct ?: hour?.cloudCoverHighPct,
                quality = DataQuality.MODEL_CURRENT,
            )
        }
        return hour
    }

    /** Hours from the current hour until sunrise (capped at 16 h; 12 h during polar day/night). */
    internal fun nightHours(now: Instant, night: NightTimes): List<Instant> {
        val start = now.truncatedTo(ChronoUnit.HOURS)
        val end = night.sunrise?.takeIf { it.isAfter(now) && Duration.between(now, it) <= Duration.ofHours(30) }
            ?: now.plus(Duration.ofHours(12))
        val sunsetStart = night.sunset?.takeIf { it.isAfter(now) }?.minus(Duration.ofHours(1))?.truncatedTo(ChronoUnit.HOURS)
        val from = sunsetStart?.takeIf { it.isAfter(start) } ?: start
        val list = mutableListOf<Instant>()
        var t = from
        while (t.isBefore(end) && list.size < 16) {
            list += t
            t = t.plus(Duration.ofHours(1))
        }
        return list
    }

    internal fun bestWindow(type: ObservationType, hourly: List<HourlyOutlook>): BestWindow? {
        val scored = hourly.filter { it.score(type) != null }
        val peak = scored.maxByOrNull { it.score(type)!! } ?: return null
        val peakScore = peak.score(type)!!
        if (peakScore < Rating.FAIR.minScore) return null
        // Extend the window around the peak while scores stay within 10 points of it.
        val idx = hourly.indexOf(peak)
        var lo = idx
        var hi = idx
        while (lo - 1 >= 0 && (hourly[lo - 1].score(type) ?: -100) >= peakScore - 10) lo--
        while (hi + 1 < hourly.size && (hourly[hi + 1].score(type) ?: -100) >= peakScore - 10) hi++
        return BestWindow(type, hourly[lo].time, hourly[hi].time.plus(Duration.ofHours(1)), peakScore)
    }
}

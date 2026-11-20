package io.github.jimthefatjam.polaris.core.model

import java.time.Instant

enum class TwilightPhase(val label: String) {
    DAY("Daylight"),
    CIVIL("Civil twilight"),
    NAUTICAL("Nautical twilight"),
    ASTRONOMICAL("Astronomical twilight"),
    NIGHT("Astronomical night");

    companion object {
        /** Classify by the Sun's geometric centre altitude (standard USNO definitions). */
        fun fromSunAltitude(alt: Double): TwilightPhase = when {
            alt > -0.833 -> DAY
            alt > -6.0 -> CIVIL
            alt > -12.0 -> NAUTICAL
            alt > -18.0 -> ASTRONOMICAL
            else -> NIGHT
        }
    }
}

data class MoonInfo(
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    /** 0..1 */
    val illuminatedFraction: Double,
    /** Ecliptic longitude difference Moon − Sun, 0..360 (0 new, 90 first quarter, 180 full, 270 last quarter). */
    val phaseAngleDeg: Double,
    val phaseName: String,
    val riseSet: RiseSet,
    val nextNewMoon: Instant?,
    val nextFullMoon: Instant?,
)

/** Twilight boundaries for the night that contains or follows a reference instant. */
data class NightTimes(
    val sunset: Instant?,
    val civilDusk: Instant?,
    val nauticalDusk: Instant?,
    val astronomicalDusk: Instant?,
    val astronomicalDawn: Instant?,
    val nauticalDawn: Instant?,
    val civilDawn: Instant?,
    val sunrise: Instant?,
    /** True when the Sun never reaches −18° (e.g. high-latitude summer). */
    val noAstronomicalDarkness: Boolean,
    /** True when the Sun never sets (polar day) for this night window. */
    val polarDay: Boolean = false,
    /** True when the Sun never rises (polar night). */
    val polarNight: Boolean = false,
)

/** Snapshot of the sky at one instant used by the scoring engine. */
data class SkySnapshot(
    val time: Instant,
    val sunAltitudeDeg: Double,
    val twilight: TwilightPhase,
    val moon: MoonInfo,
    /** Altitudes of naked-eye planets (Mercury–Saturn) keyed by name. */
    val planetAltitudes: Map<String, Double>,
)

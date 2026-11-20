package io.github.jimthefatjam.polaris.core.model

import java.time.Instant

enum class ObjectType(val label: String) {
    SUN("Star (Sun)"),
    MOON("Moon"),
    PLANET("Planet"),
    STAR("Star"),
    DOUBLE_STAR("Double star"),
    OPEN_CLUSTER("Open cluster"),
    GLOBULAR_CLUSTER("Globular cluster"),
    NEBULA("Nebula"),
    PLANETARY_NEBULA("Planetary nebula"),
    GALAXY("Galaxy"),
    ;

    val isDeepSky: Boolean
        get() = this in setOf(OPEN_CLUSTER, GLOBULAR_CLUSTER, NEBULA, PLANETARY_NEBULA, GALAXY)
}

/**
 * Catalog entry. Solar-system bodies have no fixed coordinates; fixed objects carry J2000 RA/Dec.
 */
data class CelestialObject(
    val id: String,
    val name: String,
    val type: ObjectType,
    /** Visual magnitude. For solar-system bodies this is a typical value; live values are computed. */
    val magnitude: Double?,
    val raHoursJ2000: Double? = null,
    val decDegJ2000: Double? = null,
    val constellation: String? = null,
    val catalogIds: List<String> = emptyList(),
    val description: String = "",
    /** Apparent size in arcminutes (deep-sky objects), if known. */
    val sizeArcmin: Double? = null,
) {
    val isFixed: Boolean get() = raHoursJ2000 != null && decDegJ2000 != null
}

enum class Visibility(val label: String) {
    WELL_PLACED("Well placed"),
    LOW("Low in the sky"),
    BELOW_HORIZON("Below horizon"),
    LOST_IN_TWILIGHT("Too bright a sky"),
    DAYTIME("Daytime"),
}

data class RiseSet(
    val rise: Instant?,
    val transit: Instant?,
    val set: Instant?,
    val alwaysUp: Boolean = false,
    val neverUp: Boolean = false,
)

/** Position and visibility of one object at one instant for one observer. */
data class ObjectPosition(
    val obj: CelestialObject,
    val time: Instant,
    val altitudeDeg: Double,
    val azimuthDeg: Double,
    val raHours: Double,
    val decDeg: Double,
    val magnitude: Double?,
    val riseSet: RiseSet?,
    val visibility: Visibility,
    /** Illuminated fraction 0..1 (Moon and planets). */
    val illuminatedFraction: Double? = null,
    /** Angular distance from the Moon in degrees (null for the Moon itself). */
    val moonSeparationDeg: Double? = null,
    /** 0..1 heuristic of how rewarding the object is to observe right now; used for ranking. */
    val observability: Double = 0.0,
    val notes: List<String> = emptyList(),
) {
    val isAboveHorizon: Boolean get() = altitudeDeg > 0.0
}

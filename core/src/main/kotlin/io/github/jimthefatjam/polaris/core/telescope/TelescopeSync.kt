package io.github.jimthefatjam.polaris.core.telescope

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Telescope line-of-sight sync.
 *
 * The phone's plane (or its most vertical line) is rarely exactly parallel to the telescope's line of
 * sight. When the user centres a known object in the telescope (or the phone camera looking through
 * it) and syncs, the phone's orientation R and the object's true position t are recorded. The telescope
 * axis in phone coordinates d is then solved from all syncs (see [MountingCalibration.solve]):
 *  - 1 sync: d = Rᵀ·t exactly. This absorbs both the mounting offset and the current compass error,
 *    so it is most accurate near the sync object.
 *  - ≥ 2 syncs at different altitudes: heading error and mounting are separated, which improves accuracy
 *    across the sky.
 * Afterwards, telescope pointing = R·d (+ heading correction), valid for any rotation of the tube.
 */
object TelescopeSync {
    /** Most recent syncs kept; older ones are dropped so a stale session doesn't dominate. */
    const val MAX_SYNCS = 5

    fun calibrate(sightings: List<Sighting>): CalibrationResult? =
        if (sightings.isEmpty()) null else MountingCalibration.solve(sightings, solveHeading = sightings.size >= 2)

    /**
     * How well a new sighting agrees with the existing calibration (deg). A large value means the
     * phone moved on the tube or the wrong object was centred.
     */
    fun disagreementDeg(existing: CalibrationResult, s: Sighting): Double =
        MountingCalibration.residualDeg(s, existing.axis, existing.headingCorrectionDeg)
}

/** A sync persisted in settings (rotation matrix + target), so the calibration survives restarts. */
@Serializable
data class StoredSighting(
    val rotation: List<Double>,
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val name: String,
    val declinationDeg: Double,
    val timeEpochMs: Long,
    val headingSource: String,
) {
    fun toSighting() = Sighting(rotation.toDoubleArray(), HorizontalDirection(azimuthDeg, altitudeDeg), name, declinationDeg)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun from(s: Sighting, timeEpochMs: Long, source: HeadingSource) = StoredSighting(
            s.rotation.toList(), s.target.azimuthDeg, s.target.altitudeDeg, s.targetName, s.declinationDeg, timeEpochMs, source.name,
        )

        fun listToJson(list: List<StoredSighting>): String = json.encodeToString(ListSerializer(serializer()), list)
        fun listFromJson(s: String?): List<StoredSighting> =
            if (s.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString(ListSerializer(serializer()), s) }.getOrDefault(emptyList())
    }
}

package io.github.jimthefatjam.polaris.core.telescope

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Persistent phone-to-telescope mounting model. See docs/TELESCOPE_MOUNTING.md.
 *
 * The telescope axis is a fixed unit vector **d** in the phone's own coordinate frame
 * (X = right edge, Y = top edge, Z = out of the screen). For a phone strapped flat on a surface
 * parallel to the tube, d ≈ (sin θ, cos θ, 0) with θ the in-plane mounting angle.
 * World pointing is R·d, which stays correct for any roll of the tube/phone.
 */

/** Minimal 3-vector helpers (kept local to avoid a math dependency). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val norm: Double get() = sqrt(this dot this)
    fun normalized(): Vec3 = norm.let { n -> if (n < 1e-12) this else Vec3(x / n, y / n, z / n) }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)

        /** Unit vector in the East-North-Up frame for an azimuth (from north through east) and altitude. */
        fun fromHorizontal(azimuthDeg: Double, altitudeDeg: Double): Vec3 {
            val a = Math.toRadians(azimuthDeg)
            val h = Math.toRadians(altitudeDeg)
            return Vec3(cos(h) * sin(a), cos(h) * cos(a), sin(h))
        }

        /** Phone-frame axis from an in-plane angle θ (clockwise from the top edge) and out-of-plane tilt e. */
        fun fromMounting(inPlaneDeg: Double, tiltDeg: Double): Vec3 {
            val t = Math.toRadians(inPlaneDeg)
            val e = Math.toRadians(tiltDeg)
            return Vec3(cos(e) * sin(t), cos(e) * cos(t), sin(e))
        }
    }
}

fun Vec3.toHorizontal(): HorizontalDirection {
    val u = normalized()
    return HorizontalDirection(
        PointingMath.normalizeAzimuth(Math.toDegrees(atan2(u.x, u.y))),
        Math.toDegrees(asin(u.z.coerceIn(-1.0, 1.0))),
    )
}

fun angleBetweenDeg(a: Vec3, b: Vec3): Double =
    Math.toDegrees(acos(((a dot b) / (a.norm * b.norm)).coerceIn(-1.0, 1.0)))

/** Row-major 3×3 matrix-vector products. */
internal fun DoubleArray.mul(v: Vec3) = Vec3(
    this[0] * v.x + this[1] * v.y + this[2] * v.z,
    this[3] * v.x + this[4] * v.y + this[5] * v.z,
    this[6] * v.x + this[7] * v.y + this[8] * v.z,
)

internal fun DoubleArray.mulTransposed(v: Vec3) = Vec3(
    this[0] * v.x + this[3] * v.y + this[6] * v.z,
    this[1] * v.x + this[4] * v.y + this[7] * v.z,
    this[2] * v.x + this[5] * v.y + this[8] * v.z,
)

/** Rotate a world vector about the vertical so that its azimuth increases by [deg]. */
internal fun Vec3.rotateAzimuth(deg: Double): Vec3 {
    val r = Math.toRadians(deg)
    return Vec3(x * cos(r) + y * sin(r), -x * sin(r) + y * cos(r), z)
}

/** Which rotation sensor provides the heading reference. */
enum class HeadingSource(val label: String, val description: String) {
    COMPASS("Compass", "Absolute heading from the magnetometer. No drift, but sensitive to metal and magnets."),
    GYRO("Gyro only", "Ignores the magnetometer, so immune to interference, but heading drifts slowly. Needs a star sync each session."),
}

/**
 * One calibration observation: the phone's averaged rotation matrix while the telescope was
 * centred on a known object, and that object's apparent true azimuth/altitude.
 */
data class Sighting(
    val rotation: DoubleArray,
    val target: HorizontalDirection,
    val targetName: String,
    /** Magnetic declination at the time (east positive); converts true azimuth to the sensor frame. */
    val declinationDeg: Double,
) {
    init { require(rotation.size >= 9) }

    /** Target direction in the sensor's world frame (azimuth relative to the sensor's "north"). */
    fun targetInSensorFrame(headingCorrectionDeg: Double): Vec3 =
        Vec3.fromHorizontal(target.azimuthDeg - declinationDeg - headingCorrectionDeg, target.altitudeDeg)
}

data class CalibrationResult(
    /** Telescope axis in phone coordinates (unit). */
    val axis: Vec3,
    /** Heading correction β found during calibration (deg); applies only to that session. */
    val headingCorrectionDeg: Double,
    /** Angular residual of each sighting after the fit (deg). */
    val residualsDeg: List<Double>,
    /** True if heading and mounting angle could be separated (sightings varied enough). */
    val headingSolved: Boolean,
    val warnings: List<String>,
) {
    val rmsDeg: Double get() = if (residualsDeg.isEmpty()) 0.0 else sqrt(residualsDeg.sumOf { it * it } / residualsDeg.size)
    val maxDeg: Double get() = residualsDeg.maxOrNull() ?: 0.0
    val inPlaneAngleDeg: Double get() = PointingMath.normalizeAzimuth(Math.toDegrees(atan2(axis.x, axis.y)))
    val tiltDeg: Double get() = Math.toDegrees(asin(axis.z.coerceIn(-1.0, 1.0)))
}

/** A saved, reusable mounting configuration. */
@Serializable
data class MountingProfile(
    val id: String,
    val name: String,
    val axisX: Double,
    val axisY: Double,
    val axisZ: Double,
    val sightings: Int,
    val rmsDeg: Double,
    val createdAtEpochMs: Long,
    val headingSourceAtCalibration: String = HeadingSource.COMPASS.name,
) {
    val axis: Vec3 get() = Vec3(axisX, axisY, axisZ).normalized()
    val inPlaneAngleDeg: Double get() = PointingMath.normalizeAzimuth(Math.toDegrees(atan2(axis.x, axis.y)))
    val tiltDeg: Double get() = Math.toDegrees(asin(axis.z.coerceIn(-1.0, 1.0)))
    val lowConfidence: Boolean get() = sightings < 2

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun listToJson(list: List<MountingProfile>): String = json.encodeToString(ListSerializer(serializer()), list)
        fun listFromJson(s: String?): List<MountingProfile> =
            if (s.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString(ListSerializer(serializer()), s) }.getOrDefault(emptyList())

        fun from(result: CalibrationResult, id: String, name: String, sightings: Int, now: Long, source: HeadingSource) =
            MountingProfile(id, name, result.axis.x, result.axis.y, result.axis.z, sightings, result.rmsDeg, now, source.name)
    }
}

object MountingCalibration {

    /** RMS increase (deg) that a ±5° heading change must cause for heading to count as observable. */
    private const val SEPARABILITY_THRESHOLD_DEG = 0.75
    const val TILT_WARNING_DEG = 5.0

    /**
     * Fit the telescope axis (and, when possible, the heading correction) to the sightings.
     * @param solveHeading false to trust the sensor heading (β = 0), e.g. compass mode with few sightings.
     */
    fun solve(sightings: List<Sighting>, solveHeading: Boolean = true): CalibrationResult {
        require(sightings.isNotEmpty()) { "at least one sighting is required" }
        val warnings = mutableListOf<String>()

        var beta = 0.0
        var headingSolved = false
        if (solveHeading && sightings.size >= 2) {
            val best = minimizeHeading(sightings)
            val r0 = rms(sightings, best)
            val sensitivity = minOf(rms(sightings, best + 5.0), rms(sightings, best - 5.0)) - r0
            if (sensitivity >= SEPARABILITY_THRESHOLD_DEG) {
                beta = best
                headingSolved = true
            } else {
                warnings += "Sightings were too similar to separate heading error from the mounting angle. " +
                    "Add an object at a clearly different altitude (20° or more apart)."
            }
        } else if (sightings.size == 1) {
            warnings += "Only one sighting: the result includes today's compass error. Add 2–4 more for a reliable profile."
        }

        val axis = axisFor(sightings, beta)
        val residuals = sightings.map { residualDeg(it, axis, beta) }
        val tilt = Math.toDegrees(asin(axis.z.coerceIn(-1.0, 1.0)))
        if (abs(tilt) > TILT_WARNING_DEG) {
            warnings += "The fitted axis is %.1f° out of the phone's plane. Check that the phone lies flat and parallel to the tube.".format(tilt)
        }
        val rms = sqrt(residuals.sumOf { it * it } / residuals.size)
        if (sightings.size >= 3 && rms > 2.0) {
            warnings += "Residuals are large (RMS %.1f°). A sighting may be off-centre or the phone moved; consider recording again.".format(rms)
        }
        return CalibrationResult(axis, PointingMath.normalizeAzimuth(beta + 180) - 180, residuals, headingSolved, warnings)
    }

    /** Best unit axis for a fixed heading correction: maximises Σ cos(angle) between R·d and the targets. */
    internal fun axisFor(sightings: List<Sighting>, betaDeg: Double): Vec3 =
        sightings.fold(Vec3.ZERO) { acc, s -> acc + s.rotation.mulTransposed(s.targetInSensorFrame(betaDeg)) }.normalized()

    internal fun residualDeg(s: Sighting, axis: Vec3, betaDeg: Double): Double =
        angleBetweenDeg(s.rotation.mul(axis), s.targetInSensorFrame(betaDeg))

    private fun rms(sightings: List<Sighting>, betaDeg: Double): Double {
        val axis = axisFor(sightings, betaDeg)
        return sqrt(sightings.sumOf { residualDeg(it, axis, betaDeg).let { r -> r * r } } / sightings.size)
    }

    /** Coarse 1° scan over the full circle, then golden-section refinement. */
    private fun minimizeHeading(sightings: List<Sighting>): Double {
        var bestBeta = 0.0
        var bestCost = Double.MAX_VALUE
        var b = -180.0
        while (b < 180.0) {
            val c = rms(sightings, b)
            if (c < bestCost) { bestCost = c; bestBeta = b }
            b += 1.0
        }
        var lo = bestBeta - 1.0
        var hi = bestBeta + 1.0
        val g = (sqrt(5.0) - 1) / 2
        var x1 = hi - g * (hi - lo)
        var x2 = lo + g * (hi - lo)
        var f1 = rms(sightings, x1)
        var f2 = rms(sightings, x2)
        repeat(40) {
            if (f1 < f2) { hi = x2; x2 = x1; f2 = f1; x1 = hi - g * (hi - lo); f1 = rms(sightings, x1) }
            else { lo = x1; x1 = x2; f1 = f2; x2 = lo + g * (hi - lo); f2 = rms(sightings, x2) }
        }
        return (lo + hi) / 2
    }

    data class SyncResult(val headingCorrectionDeg: Double, val altitudeErrorDeg: Double) {
        /** Altitude doesn't depend on heading; a large error means the phone moved on its mount. */
        val mountMayHaveShifted: Boolean get() = abs(altitudeErrorDeg) > 2.0
    }

    /** One-star session sync: heading correction β for a saved axis. */
    fun sync(rotation: DoubleArray, axis: Vec3, target: HorizontalDirection, declinationDeg: Double): SyncResult {
        val raw = rotation.mul(axis).toHorizontal()
        val beta = PointingMath.signedAngleDiff(raw.azimuthDeg, target.azimuthDeg - declinationDeg)
        return SyncResult(beta, target.altitudeDeg - raw.altitudeDeg)
    }

    /** Telescope pointing (true az/alt) from the live rotation, the saved axis and the heading corrections. */
    fun pointing(rotation: DoubleArray, axis: Vec3, declinationDeg: Double, headingCorrectionDeg: Double): HorizontalDirection {
        val raw = rotation.mul(axis).toHorizontal()
        return HorizontalDirection(PointingMath.normalizeAzimuth(raw.azimuthDeg + declinationDeg + headingCorrectionDeg), raw.altitudeDeg)
    }

    /**
     * Average a burst of rotation matrices (element-wise mean, then re-orthonormalised) and report
     * the spread so shaky samples can be rejected.
     * @return averaged matrix and the maximum angular deviation (deg) of the phone's Y and Z axes from the mean.
     */
    fun average(rotations: List<DoubleArray>): Pair<DoubleArray, Double> {
        require(rotations.isNotEmpty())
        val m = DoubleArray(9) { i -> rotations.sumOf { it[i] } / rotations.size }
        val avg = orthonormalize(m)
        val yAvg = avg.mul(Vec3(0.0, 1.0, 0.0))
        val zAvg = avg.mul(Vec3(0.0, 0.0, 1.0))
        val spread = rotations.maxOf { r ->
            maxOf(angleBetweenDeg(r.mul(Vec3(0.0, 1.0, 0.0)), yAvg), angleBetweenDeg(r.mul(Vec3(0.0, 0.0, 1.0)), zAvg))
        }
        return avg to spread
    }

    /** Gram–Schmidt on the columns (the images of the device axes). */
    internal fun orthonormalize(m: DoubleArray): DoubleArray {
        val c0 = Vec3(m[0], m[3], m[6]).normalized()
        var c1 = Vec3(m[1], m[4], m[7])
        c1 = (c1 + c0 * -(c0 dot c1)).normalized()
        val c2 = Vec3(c0.y * c1.z - c0.z * c1.y, c0.z * c1.x - c0.x * c1.z, c0.x * c1.y - c0.y * c1.x)
        return doubleArrayOf(c0.x, c1.x, c2.x, c0.y, c1.y, c2.y, c0.z, c1.z, c2.z)
    }
}

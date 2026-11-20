package io.github.jimthefatjam.polaris.core.telescope

import io.github.jimthefatjam.polaris.core.astro.SkyCalculator
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Which physical axis of the phone is parallel to the telescope tube. */
enum class DeviceAxis(val label: String, val x: Double, val y: Double, val z: Double) {
    /** Phone strapped flat on the tube, top edge pointing at the sky (most common for push-to mounts). */
    TOP_EDGE("Top edge of phone", 0.0, 1.0, 0.0),
    /** Phone held so its rear camera looks along the tube. */
    BACK_CAMERA("Rear camera direction", 0.0, 0.0, -1.0),
}

data class HorizontalDirection(val azimuthDeg: Double, val altitudeDeg: Double)

data class PushToGuide(
    /** Signed azimuth change, −180..180. Positive = rotate clockwise (towards east from north). */
    val deltaAzimuthDeg: Double,
    /** Signed altitude change. Positive = raise the tube. */
    val deltaAltitudeDeg: Double,
    val separationDeg: Double,
)

/**
 * Pure orientation math, independent of Android so it can be unit-tested.
 *
 * Android's SensorManager.getRotationMatrixFromVector() yields a matrix R that maps device
 * coordinates to world coordinates with X = East, Y = magnetic North, Z = Up.
 */
object PointingMath {

    /** @param r row-major 3×3 rotation matrix (9 elements). */
    fun pointing(r: DoubleArray, axis: DeviceAxis): HorizontalDirection {
        require(r.size >= 9) { "rotation matrix must have 9 elements" }
        val wx = r[0] * axis.x + r[1] * axis.y + r[2] * axis.z
        val wy = r[3] * axis.x + r[4] * axis.y + r[5] * axis.z
        val wz = r[6] * axis.x + r[7] * axis.y + r[8] * axis.z
        val norm = sqrt(wx * wx + wy * wy + wz * wz).takeIf { it > 1e-9 } ?: 1.0
        val alt = Math.toDegrees(asin((wz / norm).coerceIn(-1.0, 1.0)))
        val az = normalizeAzimuth(Math.toDegrees(atan2(wx, wy)))
        return HorizontalDirection(az, alt)
    }

    /** Converts a magnetic azimuth to true azimuth using the local magnetic declination (east positive). */
    fun magneticToTrue(azimuthMagneticDeg: Double, declinationDeg: Double) =
        normalizeAzimuth(azimuthMagneticDeg + declinationDeg)

    fun normalizeAzimuth(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

    /** Signed smallest difference b − a in degrees, in (−180, 180]. */
    fun signedAngleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    fun guide(current: HorizontalDirection, target: HorizontalDirection) = PushToGuide(
        deltaAzimuthDeg = signedAngleDiff(current.azimuthDeg, target.azimuthDeg),
        deltaAltitudeDeg = target.altitudeDeg - current.altitudeDeg,
        separationDeg = SkyCalculator.angularSeparation(current.altitudeDeg, current.azimuthDeg, target.altitudeDeg, target.azimuthDeg),
    )
}

/**
 * One-star alignment: the user centres a known object and the azimuth/altitude offsets between
 * where the phone thinks it points and where the object really is are stored and applied.
 * This corrects compass bias, magnetic declination errors and phone-to-tube mounting offsets
 * near the alignment star; it cannot correct a tilted mount base (that needs a 2-star model).
 */
data class AlignmentOffset(val azimuthDeg: Double = 0.0, val altitudeDeg: Double = 0.0, val referenceName: String? = null) {
    val isAligned: Boolean get() = referenceName != null

    fun apply(raw: HorizontalDirection) = HorizontalDirection(
        PointingMath.normalizeAzimuth(raw.azimuthDeg + azimuthDeg),
        (raw.altitudeDeg + altitudeDeg).coerceIn(-90.0, 90.0),
    )

    companion object {
        fun from(measured: HorizontalDirection, actual: HorizontalDirection, name: String) = AlignmentOffset(
            PointingMath.signedAngleDiff(measured.azimuthDeg, actual.azimuthDeg),
            actual.altitudeDeg - measured.altitudeDeg,
            name,
        )
    }
}

/**
 * Exponential smoothing that treats azimuth as a circular quantity (averaging 359° and 1° gives 0°,
 * not 180°). [alpha] in (0, 1]: higher reacts faster, lower is steadier.
 */
class DirectionSmoother(private val alpha: Double = 0.15) {
    private var sx = 0.0
    private var sy = 0.0
    private var alt = 0.0
    private var initialised = false

    fun update(d: HorizontalDirection): HorizontalDirection {
        val rad = Math.toRadians(d.azimuthDeg)
        if (!initialised) {
            sx = sin(rad); sy = cos(rad); alt = d.altitudeDeg; initialised = true
        } else {
            sx += alpha * (sin(rad) - sx)
            sy += alpha * (cos(rad) - sy)
            alt += alpha * (d.altitudeDeg - alt)
        }
        return HorizontalDirection(PointingMath.normalizeAzimuth(Math.toDegrees(atan2(sx, sy))), alt)
    }

    fun reset() { initialised = false }
}

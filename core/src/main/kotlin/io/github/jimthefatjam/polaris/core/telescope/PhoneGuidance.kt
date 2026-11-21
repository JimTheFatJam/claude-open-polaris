package io.github.jimthefatjam.polaris.core.telescope

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * The two navigation modes of the Telescope screen.
 *
 * All functions take Android's device→world rotation matrix R (row-major; world X = east,
 * Y = the sensor's north, Z = up) plus the heading corrections that turn the sensor's north into
 * true north: the magnetic declination (compass mode) and a session heading correction from a
 * star sync. Device axes: X = right edge, Y = top edge, Z = out of the screen.
 */
object PhoneGuidance {

    private val BACK = Vec3(0.0, 0.0, -1.0)
    private val SCREEN_NORMAL = Vec3(0.0, 0.0, 1.0)

    /** Target direction expressed in the sensor's world frame. */
    fun targetInSensorFrame(target: HorizontalDirection, declinationDeg: Double, headingCorrectionDeg: Double): Vec3 =
        Vec3.fromHorizontal(target.azimuthDeg - declinationDeg - headingCorrectionDeg, target.altitudeDeg)

    private fun toTrue(v: Vec3, declinationDeg: Double, headingCorrectionDeg: Double): HorizontalDirection {
        val h = v.toHorizontal()
        return HorizontalDirection(PointingMath.normalizeAzimuth(h.azimuthDeg + declinationDeg + headingCorrectionDeg), h.altitudeDeg)
    }

    // ------------------------------------------------------------------ Mode 1: camera

    /** Where the back of the phone (rear camera) points, in true azimuth/altitude. */
    fun cameraPointing(r: DoubleArray, declinationDeg: Double, headingCorrectionDeg: Double): HorizontalDirection =
        toTrue(r.mul(BACK), declinationDeg, headingCorrectionDeg)

    /**
     * One-star heading sync in camera mode: with the back of the phone aimed at a known object,
     * the azimuth difference is the session heading correction. Altitude needs no correction
     * (gravity), so its error is reported as a sanity check.
     */
    fun cameraSync(r: DoubleArray, target: HorizontalDirection, declinationDeg: Double): MountingCalibration.SyncResult =
        MountingCalibration.sync(r, BACK, target, declinationDeg)

    // ------------------------------------------------------------------ Mode 2: phone plane

    /** Direction the screen faces (the plane's normal), in true azimuth/altitude. */
    fun planeNormal(r: DoubleArray, declinationDeg: Double, headingCorrectionDeg: Double): HorizontalDirection =
        toTrue(r.mul(SCREEN_NORMAL), declinationDeg, headingCorrectionDeg)

    /**
     * How far the target is from lying in the phone's plane, and which way to tilt the phone.
     * Spinning the phone within its own plane does not change [offsetDeg].
     */
    fun planeGuide(r: DoubleArray, target: HorizontalDirection, declinationDeg: Double, headingCorrectionDeg: Double): PlaneGuide {
        val t = r.mulTransposed(targetInSensorFrame(target, declinationDeg, headingCorrectionDeg)).normalized()
        val offset = Math.toDegrees(asin(t.z.coerceIn(-1.0, 1.0)))
        val inPlaneLength = kotlin.math.sqrt(t.x * t.x + t.y * t.y)
        val direction = if (inPlaneLength < 1e-6) 0.0 else PointingMath.normalizeAzimuth(Math.toDegrees(atan2(t.x, t.y)))
        // Bringing the target into the plane = rotating the edge that faces the target towards
        // the side the target is on (screen side if offset > 0, back side otherwise).
        val edgeToMove = if (offset >= 0) direction else PointingMath.normalizeAzimuth(direction + 180)
        val screenUp = r[8] // world-Z component of the screen normal
        val move = when {
            abs(screenUp) < 0.35 -> EdgeMove.TOWARD_SCREEN_SIDE
            screenUp > 0 -> EdgeMove.RAISE
            else -> EdgeMove.LOWER
        }
        return PlaneGuide(offset, direction, edgeToMove, move)
    }
}

enum class EdgeMove { RAISE, LOWER, TOWARD_SCREEN_SIDE }

/**
 * @property offsetDeg signed angle between the target and the phone's plane; positive = the target
 *   is on the screen side. 0 means the plane passes through the target.
 * @property inPlaneDirectionDeg where the target lies within the plane, clockwise from the top edge
 *   (0 = beyond the top edge, 90 = beyond the right edge).
 * @property edgeDeg which edge to move toward the screen side (same angular convention).
 * @property move how to move that edge, using gravity to say "raise"/"lower" when the screen is not
 *   close to vertical.
 */
data class PlaneGuide(val offsetDeg: Double, val inPlaneDirectionDeg: Double, val edgeDeg: Double, val move: EdgeMove) {
    val aligned: Boolean get() = abs(offsetDeg) < ALIGNED_DEG

    fun instruction(): String {
        if (aligned) return "Aligned: the phone's plane passes through the target"
        val edge = edgeName(edgeDeg)
        val amount = "%.0f°".format(abs(offsetDeg))
        return when (move) {
            EdgeMove.RAISE -> "Raise the $edge edge $amount"
            EdgeMove.LOWER -> "Lower the $edge edge $amount"
            // The screen faces sideways, so "up/down" is ambiguous; describe the tilt relative to the screen.
            EdgeMove.TOWARD_SCREEN_SIDE -> "Tilt the $edge edge $amount toward the screen side"
        }
    }

    companion object {
        const val ALIGNED_DEG = 1.0
        private val names = listOf("top", "top-right", "right", "bottom-right", "bottom", "bottom-left", "left", "top-left")
        fun edgeName(deg: Double): String = names[((PointingMath.normalizeAzimuth(deg) + 22.5) / 45.0).toInt() % 8]
    }
}

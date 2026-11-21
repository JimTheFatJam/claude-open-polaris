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
     * The plane's "most vertical line": the steepest-ascent direction within the screen plane,
     * i.e. world-up projected onto the plane, u − (u·n)n. It depends only on the plane's tilt, so it
     * is unchanged when the phone spins within its own plane. Its altitude is 90° − |plane tilt|.
     * Returns null when the phone lies (almost) level: a horizontal plane has no vertical line.
     */
    fun planeVerticalLine(r: DoubleArray, declinationDeg: Double, headingCorrectionDeg: Double): HorizontalDirection? {
        val n = r.mul(SCREEN_NORMAL).normalized()
        val up = Vec3(0.0, 0.0, 1.0)
        val p = up + n * -(up dot n)
        if (p.norm < LEVEL_EPSILON) return null
        return toTrue(p, declinationDeg, headingCorrectionDeg)
    }

    /** sin(1.5°): within 1.5° of level the vertical line is undefined/unstable. */
    private const val LEVEL_EPSILON = 0.026

    /**
     * Guidance for plane mode. The target must lie in the phone's plane AND on the plane's most
     * vertical line; together these pin down a single pointing direction ([PlaneGuide.verticalLine]).
     * Spinning the phone within its own plane changes neither condition.
     */
    fun planeGuide(r: DoubleArray, target: HorizontalDirection, declinationDeg: Double, headingCorrectionDeg: Double): PlaneGuide {
        val tWorld = targetInSensorFrame(target, declinationDeg, headingCorrectionDeg)
        val t = r.mulTransposed(tWorld).normalized()
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
        val line = planeVerticalLine(r, declinationDeg, headingCorrectionDeg)
        // Direction of the vertical line within the plane, clockwise from the top edge.
        val lineDev = r.mulTransposed(Vec3(0.0, 0.0, 1.0)).let { u -> Vec3(u.x, u.y, 0.0) }
        val lineDirection = if (line == null || lineDev.norm < 1e-9) null
        else PointingMath.normalizeAzimuth(Math.toDegrees(atan2(lineDev.x, lineDev.y)))
        val guide = line?.let { PointingMath.guide(it, target) }
        return PlaneGuide(offset, direction, edgeToMove, move, line, lineDirection, guide)
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
data class PlaneGuide(
    val offsetDeg: Double,
    val inPlaneDirectionDeg: Double,
    val edgeDeg: Double,
    val move: EdgeMove,
    /** True az/alt of the plane's most vertical line; null when the phone is level. */
    val verticalLine: HorizontalDirection? = null,
    /** Where the vertical line runs within the phone's plane, clockwise from the top edge. */
    val verticalLineInPlaneDeg: Double? = null,
    /** Correction from the vertical line to the target (turn about the vertical, steepen/flatten). */
    val lineGuide: PushToGuide? = null,
) {
    /** The target lies in the plane (one condition). */
    val inPlane: Boolean get() = abs(offsetDeg) < ALIGNED_DEG

    /** Angle between the plane's most vertical line and the target (pinpoint error). */
    val separationDeg: Double? get() = lineGuide?.separationDeg

    /** Pinpoint alignment: the target is on the plane's most vertical line. */
    val aligned: Boolean get() = (separationDeg ?: 180.0) < ALIGNED_DEG

    /**
     * Primary instruction: bring the plane's most vertical line onto the target by turning the
     * phone about the vertical (azimuth) and making it steeper or flatter (the line's altitude is
     * 90° − the phone's tilt).
     */
    fun instruction(): String {
        val g = lineGuide ?: return "Tilt the phone off level: a flat phone has no vertical line"
        if (aligned) return "On target: the plane's vertical line points at the object"
        val turn = if (abs(g.deltaAzimuthDeg) < ALIGNED_DEG) null
        else (if (g.deltaAzimuthDeg > 0) "turn right " else "turn left ") + "%.0f°".format(abs(g.deltaAzimuthDeg))
        val tilt = if (abs(g.deltaAltitudeDeg) < ALIGNED_DEG) null
        else (if (g.deltaAltitudeDeg > 0) "make the phone steeper " else "make the phone flatter ") + "%.0f°".format(abs(g.deltaAltitudeDeg))
        return listOfNotNull(turn, tilt).joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    /** Secondary instruction: just get the target into the plane (which edge to raise/lower). */
    fun planeInstruction(): String {
        if (inPlane) return "The plane passes through the target"
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

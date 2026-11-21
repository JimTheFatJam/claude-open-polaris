package io.github.jimthefatjam.polaris.core.telescope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PhoneGuidanceTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** Device→world: yaw about Up (clockwise from north), pitch about device X, roll about device Y, spin about device Z. */
    private fun pose(yaw: Double, pitch: Double, roll: Double = 0.0, spin: Double = 0.0): DoubleArray {
        fun m(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * b[k][j] } } }
        val y = Math.toRadians(-yaw); val p = Math.toRadians(pitch); val r = Math.toRadians(roll); val s = Math.toRadians(-spin)
        val rz = arrayOf(doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(p), -sin(p)), doubleArrayOf(0.0, sin(p), cos(p)))
        val ry = arrayOf(doubleArrayOf(cos(r), 0.0, sin(r)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(r), 0.0, cos(r)))
        val sz = arrayOf(doubleArrayOf(cos(s), -sin(s), 0.0), doubleArrayOf(sin(s), cos(s), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val out = m(m(m(rz, rx), ry), sz)
        return DoubleArray(9) { out[it / 3][it % 3] }
    }

    @Test fun cameraModePointsOutOfTheBack() {
        // Phone upright (screen facing the user, pitched 90°), facing north: the back looks north at the horizon.
        val p = PhoneGuidance.cameraPointing(pose(0.0, 90.0), 0.0, 0.0)
        assertEquals(0.0, p.altitudeDeg, 1e-6)
        assertEquals(180.0, PointingMath.normalizeAzimuth(p.azimuthDeg + 180), 1e-6) // azimuth 0
        // Flat phone face-up: back points straight down.
        assertEquals(-90.0, PhoneGuidance.cameraPointing(identity, 0.0, 0.0).altitudeDeg, 1e-6)
    }

    @Test fun cameraModeAppliesDeclinationAndSessionHeading() {
        val p = PhoneGuidance.cameraPointing(pose(100.0, 120.0), declinationDeg = 4.0, headingCorrectionDeg = -6.0)
        val raw = PhoneGuidance.cameraPointing(pose(100.0, 120.0), 0.0, 0.0)
        assertEquals(-2.0, PointingMath.signedAngleDiff(raw.azimuthDeg, p.azimuthDeg), 1e-6)
    }

    @Test fun cameraSyncRecoversHeading() {
        val r = pose(40.0, 110.0)
        val raw = PhoneGuidance.cameraPointing(r, 0.0, 0.0)
        val truth = HorizontalDirection(PointingMath.normalizeAzimuth(raw.azimuthDeg + 3.0 + 7.5), raw.altitudeDeg)
        val sync = PhoneGuidance.cameraSync(r, truth, 3.0)
        assertEquals(7.5, sync.headingCorrectionDeg, 1e-6)
        assertEquals(0.0, sync.altitudeErrorDeg, 1e-6)
    }

    @Test fun flatPhoneTargetEastRaiseRightEdge() {
        val g = PhoneGuidance.planeGuide(identity, HorizontalDirection(90.0, 30.0), 0.0, 0.0)
        assertEquals(30.0, g.offsetDeg, 1e-6)
        assertEquals(90.0, g.inPlaneDirectionDeg, 1e-6)
        assertEquals("Raise the right edge 30°", g.planeInstruction())
    }

    @Test fun followingTheInstructionAligns() {
        // Raise the right (east) edge of a flat phone by 30°: rotate about the device Y axis.
        val tilted = pose(0.0, 0.0, roll = -30.0)
        val g = PhoneGuidance.planeGuide(tilted, HorizontalDirection(90.0, 30.0), 0.0, 0.0)
        assertTrue("offset ${g.offsetDeg}", g.inPlane)
    }

    @Test fun spinningWithinThePlaneDoesNotChangeAlignment() {
        val target = HorizontalDirection(215.0, 47.0)
        val base = PhoneGuidance.planeGuide(pose(30.0, 50.0, 10.0), target, 2.0, 1.0)
        listOf(15.0, 90.0, 180.0, 271.0).forEach { spin ->
            val g = PhoneGuidance.planeGuide(pose(30.0, 50.0, 10.0, spin), target, 2.0, 1.0)
            assertEquals("spin $spin", base.offsetDeg, g.offsetDeg, 1e-6)
            // The target's direction within the plane rotates with the phone.
            assertEquals(0.0, PointingMath.signedAngleDiff(base.inPlaneDirectionDeg - spin, g.inPlaneDirectionDeg), 1e-6)
        }
    }

    @Test fun planeContainingTargetIsAligned() {
        // Build a pose whose plane contains a known target: point the top edge at it.
        val target = HorizontalDirection(123.0, 38.0)
        val r = pose(123.0, 38.0) // pitch about X raises the top edge to the target's altitude
        val g = PhoneGuidance.planeGuide(r, target, 0.0, 0.0)
        assertTrue(g.inPlane)
        // Pitching about X makes the top edge the steepest line, so this is also a pinpoint alignment.
        assertTrue("sep ${g.separationDeg}", g.aligned)
        assertEquals(0.0, g.inPlaneDirectionDeg, 1e-6) // beyond the top edge
        assertTrue(g.instruction().startsWith("On target"))
    }

    @Test fun targetBehindPlaneTiltsOppositeEdge() {
        // Phone face-up but tilted so that an east target at low altitude is on the back side.
        val r = pose(0.0, 0.0, roll = -50.0) // right edge raised 50°
        val g = PhoneGuidance.planeGuide(r, HorizontalDirection(90.0, 10.0), 0.0, 0.0)
        assertTrue(g.offsetDeg < 0)
        assertEquals("left", PlaneGuide.edgeName(g.edgeDeg))
        assertEquals(EdgeMove.RAISE, g.move)
        // Raising the left edge = lowering the right edge by |offset| → aligned.
        val fixed = PhoneGuidance.planeGuide(pose(0.0, 0.0, roll = -50.0 - g.offsetDeg), HorizontalDirection(90.0, 10.0), 0.0, 0.0)
        assertTrue("offset ${fixed.offsetDeg}", fixed.inPlane)
    }

    @Test fun verticalScreenUsesScreenRelativeWording() {
        val g = PhoneGuidance.planeGuide(pose(0.0, 90.0), HorizontalDirection(0.0, 30.0), 0.0, 0.0)
        assertEquals(EdgeMove.TOWARD_SCREEN_SIDE, g.move)
        assertFalse(g.inPlane)
        assertTrue(g.planeInstruction().contains("toward the screen side"))
    }

    @Test fun headingCorrectionMovesTheTargetConsistently() {
        val r = pose(10.0, 35.0, 5.0)
        val t = HorizontalDirection(80.0, 25.0)
        val a = PhoneGuidance.planeGuide(r, t, declinationDeg = 5.0, headingCorrectionDeg = 3.0)
        val b = PhoneGuidance.planeGuide(r, HorizontalDirection(72.0, 25.0), 0.0, 0.0)
        assertEquals(a.offsetDeg, b.offsetDeg, 1e-9)
    }

    @Test fun edgeNames() {
        assertEquals("top", PlaneGuide.edgeName(0.0))
        assertEquals("top", PlaneGuide.edgeName(359.0))
        assertEquals("top-right", PlaneGuide.edgeName(45.0))
        assertEquals("right", PlaneGuide.edgeName(100.0))
        assertEquals("bottom", PlaneGuide.edgeName(180.0))
        assertEquals("left", PlaneGuide.edgeName(-90.0))
    }

    // ------------------------------------------------------------------ most vertical line

    @Test fun verticalLineOfPitchedPhoneIsTheRaisedTopEdge() {
        val line = PhoneGuidance.planeVerticalLine(pose(0.0, 40.0), 0.0, 0.0)!!
        assertEquals(0.0, PointingMath.signedAngleDiff(0.0, line.azimuthDeg), 1e-6)
        assertEquals(40.0, line.altitudeDeg, 1e-6)
    }

    @Test fun verticalLineAltitudeIs90MinusTilt() {
        // Tilt the phone sideways (raise the right edge 25°): the steepest line runs toward the right edge.
        val r = pose(30.0, 0.0, roll = -25.0)
        val line = PhoneGuidance.planeVerticalLine(r, 0.0, 0.0)!!
        assertEquals(25.0, line.altitudeDeg, 1e-6)
        val g = PhoneGuidance.planeGuide(r, HorizontalDirection(0.0, 10.0), 0.0, 0.0)
        assertEquals("right", PlaneGuide.edgeName(g.verticalLineInPlaneDeg!!))
        // A vertical screen: the steepest line is straight up.
        assertEquals(90.0, PhoneGuidance.planeVerticalLine(pose(77.0, 90.0), 0.0, 0.0)!!.altitudeDeg, 1e-6)
    }

    @Test fun levelPhoneHasNoVerticalLine() {
        assertEquals(null, PhoneGuidance.planeVerticalLine(identity, 0.0, 0.0))
        val g = PhoneGuidance.planeGuide(identity, HorizontalDirection(90.0, 30.0), 0.0, 0.0)
        assertFalse(g.aligned)
        assertTrue(g.instruction().contains("off level"))
    }

    @Test fun verticalLineIgnoresSpinWithinThePlane() {
        val a = PhoneGuidance.planeVerticalLine(pose(140.0, 55.0, 12.0), 3.0, -2.0)!!
        listOf(30.0, 123.0, 250.0).forEach { spin ->
            val b = PhoneGuidance.planeVerticalLine(pose(140.0, 55.0, 12.0, spin), 3.0, -2.0)!!
            assertEquals(0.0, PointingMath.signedAngleDiff(a.azimuthDeg, b.azimuthDeg), 1e-6)
            assertEquals(a.altitudeDeg, b.altitudeDeg, 1e-6)
        }
    }

    @Test fun inPlaneButOffTheVerticalLineIsNotPinpoint() {
        // Phone pitched 40° facing north: the plane contains due east at the horizon, but its vertical line points north at 40°.
        val g = PhoneGuidance.planeGuide(pose(0.0, 40.0), HorizontalDirection(90.0, 0.0), 0.0, 0.0)
        assertTrue(g.inPlane)
        assertFalse(g.aligned)
        assertEquals(90.0, g.separationDeg!!, 1e-6)
    }

    @Test fun followingTheVerticalLineInstructionsReachesPinpoint() {
        val target = HorizontalDirection(210.0, 55.0)
        val start = PhoneGuidance.planeGuide(pose(150.0, 30.0), target, 0.0, 0.0)
        assertEquals("Turn right 60°, make the phone steeper 25°", start.instruction())
        // Turning right 60° (yaw) and steepening 25° (pitch) lands the vertical line on the target.
        val end = PhoneGuidance.planeGuide(pose(210.0, 55.0, spin = 33.0), target, 0.0, 0.0)
        assertTrue("sep ${end.separationDeg}", end.aligned)
        assertTrue(end.inPlane)
    }

    @Test fun pinpointImpliesInPlane() {
        val rnd = kotlin.random.Random(7)
        repeat(50) {
            val r = pose(rnd.nextDouble(0.0, 360.0), rnd.nextDouble(5.0, 175.0), rnd.nextDouble(-60.0, 60.0), rnd.nextDouble(0.0, 360.0))
            val line = PhoneGuidance.planeVerticalLine(r, 0.0, 0.0) ?: return@repeat
            val g = PhoneGuidance.planeGuide(r, line, 0.0, 0.0)
            assertTrue(g.aligned)
            assertEquals(0.0, g.offsetDeg, 1e-6)
        }
    }
}

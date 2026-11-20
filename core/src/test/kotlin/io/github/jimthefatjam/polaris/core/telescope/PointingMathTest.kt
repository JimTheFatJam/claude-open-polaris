package io.github.jimthefatjam.polaris.core.telescope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PointingMathTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** Rotation about world Z (up) by yaw (clockwise from north), then tilt about device X by pitch. */
    private fun matrix(yawDeg: Double, pitchDeg: Double): DoubleArray {
        val y = Math.toRadians(-yawDeg)
        val p = Math.toRadians(pitchDeg)
        val rz = arrayOf(doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(p), -sin(p)), doubleArrayOf(0.0, sin(p), cos(p)))
        val m = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) m[i * 3 + j] = (0..2).sumOf { k -> rz[i][k] * rx[k][j] }
        return m
    }

    @Test fun flatPhoneTopEdgePointsNorthAtHorizon() {
        val d = PointingMath.pointing(identity, DeviceAxis.TOP_EDGE)
        assertEquals(0.0, d.azimuthDeg, 1e-9)
        assertEquals(0.0, d.altitudeDeg, 1e-9)
    }

    @Test fun flatPhoneBackCameraPointsDown() {
        assertEquals(-90.0, PointingMath.pointing(identity, DeviceAxis.BACK_CAMERA).altitudeDeg, 1e-9)
    }

    @Test fun yawAndPitchAreRecovered() {
        val d = PointingMath.pointing(matrix(135.0, 30.0), DeviceAxis.TOP_EDGE)
        assertEquals(135.0, d.azimuthDeg, 1e-6)
        assertEquals(30.0, d.altitudeDeg, 1e-6)
        val back = PointingMath.pointing(matrix(270.0, 90.0), DeviceAxis.BACK_CAMERA)
        // Tilting the phone 90° up makes the rear camera face horizontally along the original "north" of the phone.
        assertEquals(0.0, back.altitudeDeg, 1e-6)
        assertEquals(270.0, back.azimuthDeg, 1e-6)
    }

    @Test fun declinationCorrection() {
        assertEquals(5.0, PointingMath.magneticToTrue(355.0, 10.0), 1e-9)
        assertEquals(350.0, PointingMath.magneticToTrue(5.0, -15.0), 1e-9)
    }

    @Test fun signedDifferenceWraps() {
        assertEquals(20.0, PointingMath.signedAngleDiff(350.0, 10.0), 1e-9)
        assertEquals(-20.0, PointingMath.signedAngleDiff(10.0, 350.0), 1e-9)
        assertEquals(180.0, PointingMath.signedAngleDiff(0.0, 180.0), 1e-9)
    }

    @Test fun alignmentOffsetCorrectsAcrossNorth() {
        val measured = HorizontalDirection(355.0, 40.0)
        val actual = HorizontalDirection(3.0, 42.0)
        val off = AlignmentOffset.from(measured, actual, "Vega")
        assertEquals(8.0, off.azimuthDeg, 1e-9)
        val corrected = off.apply(measured)
        assertEquals(3.0, corrected.azimuthDeg, 1e-9)
        assertEquals(42.0, corrected.altitudeDeg, 1e-9)
        assertTrue(off.isAligned)
    }

    @Test fun pushToGuide() {
        val g = PointingMath.guide(HorizontalDirection(350.0, 20.0), HorizontalDirection(10.0, 50.0))
        assertEquals(20.0, g.deltaAzimuthDeg, 1e-9)
        assertEquals(30.0, g.deltaAltitudeDeg, 1e-9)
        assertTrue(g.separationDeg in 30.0..40.0)
    }

    @Test fun smootherHandlesNorthWrap() {
        val s = DirectionSmoother(alpha = 0.5)
        s.update(HorizontalDirection(359.0, 10.0))
        val out = s.update(HorizontalDirection(1.0, 10.0))
        assertTrue("got ${out.azimuthDeg}", out.azimuthDeg > 359.0 || out.azimuthDeg < 1.0)
    }
}

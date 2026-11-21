package io.github.jimthefatjam.polaris.core.telescope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MountingCalibrationTest {

    /** Device→world rotation: yaw about Up (clockwise from north), then pitch about device X, then roll about device Y. */
    private fun pose(yawDeg: Double, pitchDeg: Double, rollDeg: Double): DoubleArray {
        val y = Math.toRadians(-yawDeg); val p = Math.toRadians(pitchDeg); val r = Math.toRadians(rollDeg)
        val rz = arrayOf(doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(p), -sin(p)), doubleArrayOf(0.0, sin(p), cos(p)))
        val ry = arrayOf(doubleArrayOf(cos(r), 0.0, sin(r)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(r), 0.0, cos(r)))
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * b[k][j] } } }
        val m = mul(mul(rz, rx), ry)
        return DoubleArray(9) { m[it / 3][it % 3] }
    }

    /** Simulate a sighting: what the sky says vs. what the (biased) sensors report. */
    private fun sighting(r: DoubleArray, axis: Vec3, beta: Double, decl: Double, noiseDeg: Double = 0.0, rnd: Random = Random(1)): Sighting {
        val raw = r.mul(axis).toHorizontal()
        fun n() = if (noiseDeg == 0.0) 0.0 else (rnd.nextDouble() * 2 - 1) * noiseDeg
        val target = HorizontalDirection(PointingMath.normalizeAzimuth(raw.azimuthDeg + decl + beta + n()), raw.altitudeDeg + n())
        return Sighting(r, target, "star", decl)
    }

    private val trueAxis = Vec3.fromMounting(inPlaneDeg = 37.0, tiltDeg = 1.5)
    private val poses = listOf(pose(20.0, 30.0, 0.0), pose(140.0, 65.0, 10.0), pose(260.0, 15.0, -20.0), pose(320.0, 45.0, 35.0))

    @Test fun recoversMountingAngleTiltAndHeadingExactly() {
        val s = poses.map { sighting(it, trueAxis, beta = 8.0, decl = -3.0) }
        val r = MountingCalibration.solve(s)
        assertTrue(r.headingSolved)
        assertEquals(37.0, r.inPlaneAngleDeg, 0.05)
        assertEquals(1.5, r.tiltDeg, 0.05)
        assertEquals(8.0, r.headingCorrectionDeg, 0.05)
        assertTrue(r.maxDeg < 0.05)
    }

    @Test fun robustToSightingNoise() {
        val rnd = Random(42)
        val s = poses.map { sighting(it, trueAxis, beta = -12.0, decl = 5.0, noiseDeg = 0.5, rnd = rnd) }
        val r = MountingCalibration.solve(s)
        assertEquals(37.0, r.inPlaneAngleDeg, 1.5)
        assertEquals(1.5, r.tiltDeg, 1.5)
        assertEquals(-12.0, r.headingCorrectionDeg, 1.5)
        assertTrue(r.rmsDeg < 1.0)
    }

    @Test fun anyInPlaneAngleWorksIncludingBehindTopEdge() {
        listOf(0.0, 90.0, 179.0, 233.0, 359.0).forEach { theta ->
            val axis = Vec3.fromMounting(theta, 0.0)
            val r = MountingCalibration.solve(poses.map { sighting(it, axis, 4.0, 0.0) })
            assertEquals("theta $theta", 0.0, PointingMath.signedAngleDiff(theta, r.inPlaneAngleDeg), 0.05)
        }
    }

    @Test fun phoneFlatAtSameAltitudeIsDegenerate() {
        // Tube horizontal, phone face-up, only yaw changes: heading and in-plane angle are indistinguishable.
        val flat = listOf(pose(10.0, 0.0, 0.0), pose(100.0, 0.0, 0.0), pose(200.0, 0.0, 0.0))
        val r = MountingCalibration.solve(flat.map { sighting(it, Vec3.fromMounting(50.0, 0.0), 10.0, 0.0) })
        assertFalse(r.headingSolved)
        assertEquals(0.0, r.headingCorrectionDeg, 1e-9)
        assertTrue(r.warnings.any { it.contains("different altitude") })
        // The heading error is absorbed into θ: 50 + 10.
        assertEquals(60.0, r.inPlaneAngleDeg, 0.1)
    }

    @Test fun singleSightingIsExactButFlagged() {
        val s = sighting(poses[1], trueAxis, beta = 0.0, decl = 2.0)
        val r = MountingCalibration.solve(listOf(s))
        assertTrue(r.maxDeg < 1e-6)
        assertTrue(r.warnings.any { it.contains("one sighting") })
    }

    @Test fun pointingIsCorrectForAnyRollAfterCalibration() {
        val cal = MountingCalibration.solve(poses.map { sighting(it, trueAxis, 6.0, -2.0) })
        // New poses, never seen during calibration, including heavy roll about the tube.
        listOf(pose(75.0, 55.0, 80.0), pose(190.0, 25.0, -60.0), pose(300.0, 70.0, 140.0)).forEach { r ->
            val truth = sighting(r, trueAxis, 6.0, -2.0).target
            val p = MountingCalibration.pointing(r, cal.axis, -2.0, cal.headingCorrectionDeg)
            val err = angleBetweenDeg(Vec3.fromHorizontal(p.azimuthDeg, p.altitudeDeg), Vec3.fromHorizontal(truth.azimuthDeg, truth.altitudeDeg))
            assertTrue("err $err", err < 0.05)
        }
    }

    @Test fun constantAzAltOffsetFailsWhereMountingModelSucceeds() {
        // Demonstrates why v0.1.0's world-frame offset is insufficient for a rotated phone.
        val topEdge = Vec3(0.0, 1.0, 0.0)
        val alignPose = pose(30.0, 20.0, 0.0)
        val measured = alignPose.mul(topEdge).toHorizontal()
        val actual = alignPose.mul(trueAxis).toHorizontal()
        val offset = AlignmentOffset.from(measured, actual, "ref")
        val elsewhere = pose(200.0, 70.0, 45.0)
        val simple = offset.apply(elsewhere.mul(topEdge).toHorizontal())
        val truth = elsewhere.mul(trueAxis).toHorizontal()
        val simpleErr = angleBetweenDeg(Vec3.fromHorizontal(simple.azimuthDeg, simple.altitudeDeg), Vec3.fromHorizontal(truth.azimuthDeg, truth.altitudeDeg))
        assertTrue("simple-mode error should be large, was $simpleErr", simpleErr > 10)
    }

    @Test fun sessionSyncRecoversHeadingAndChecksAltitude() {
        val r = pose(120.0, 40.0, 15.0)
        val s = sighting(r, trueAxis, beta = -9.0, decl = 4.0)
        val sync = MountingCalibration.sync(r, trueAxis, s.target, 4.0)
        assertEquals(-9.0, sync.headingCorrectionDeg, 1e-6)
        assertEquals(0.0, sync.altitudeErrorDeg, 1e-6)
        assertFalse(sync.mountMayHaveShifted)
        // Phone shifted 4° on its strap: altitude check catches it.
        val shifted = MountingCalibration.sync(r, Vec3.fromMounting(37.0, 6.0), s.target, 4.0)
        assertTrue(shifted.mountMayHaveShifted || kotlin.math.abs(shifted.altitudeErrorDeg) > 1.0)
    }

    @Test fun averagingRejectsShakeAndKeepsOrthonormal() {
        val still = List(20) { pose(50.0, 30.0 + (it % 2) * 0.1, 0.0) }
        val (avg, spread) = MountingCalibration.average(still)
        assertTrue(spread < 0.2)
        val c0 = Vec3(avg[0], avg[3], avg[6]); val c1 = Vec3(avg[1], avg[4], avg[7])
        assertEquals(1.0, c0.norm, 1e-9); assertEquals(0.0, c0 dot c1, 1e-9)
        val shaky = List(20) { pose(50.0 + it, 30.0, 0.0) }
        assertTrue(MountingCalibration.average(shaky).second > 5)
    }

    @Test fun profileJsonRoundTrip() {
        val r = MountingCalibration.solve(poses.map { sighting(it, trueAxis, 0.0, 0.0) })
        val p = MountingProfile.from(r, "id1", "Refractor strap", 4, 1234L, HeadingSource.GYRO)
        val back = MountingProfile.listFromJson(MountingProfile.listToJson(listOf(p)))
        assertEquals(listOf(p), back)
        assertEquals(37.0, back[0].inPlaneAngleDeg, 0.05)
        assertTrue(MountingProfile.listFromJson("not json").isEmpty())
        assertTrue(MountingProfile.listFromJson(null).isEmpty())
    }
}

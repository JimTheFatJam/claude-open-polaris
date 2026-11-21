package io.github.jimthefatjam.polaris.core.telescope

import io.github.jimthefatjam.polaris.core.astro.Catalog
import io.github.jimthefatjam.polaris.core.astro.SkyCalculator
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin

class TelescopeSyncTest {
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

    /** Telescope 6° off the phone's vertical line and slightly out of plane; compass reads 5° wrong. */
    private val scopeAxis = Vec3.fromMounting(inPlaneDeg = 6.0, tiltDeg = -2.0)
    private val compassError = 5.0

    private fun sightingAt(r: DoubleArray, name: String = "Moon"): Sighting {
        val raw = r.mul(scopeAxis).toHorizontal()
        val truth = HorizontalDirection(PointingMath.normalizeAzimuth(raw.azimuthDeg + compassError), raw.altitudeDeg)
        return Sighting(r, truth, name, 0.0)
    }

    private fun errorAfter(cal: CalibrationResult, r: DoubleArray): Double {
        val truth = sightingAt(r).target
        val p = MountingCalibration.pointing(r, cal.axis, 0.0, cal.headingCorrectionDeg)
        return angleBetweenDeg(Vec3.fromHorizontal(p.azimuthDeg, p.altitudeDeg), Vec3.fromHorizontal(truth.azimuthDeg, truth.altitudeDeg))
    }

    @Test fun noSyncNoCalibration() = assertNull(TelescopeSync.calibrate(emptyList()))

    @Test fun oneSyncRemovesErrorNearTheSyncObject() {
        val moonPose = pose(140.0, 35.0)
        val cal = TelescopeSync.calibrate(listOf(sightingAt(moonPose)))!!
        assertTrue(errorAfter(cal, moonPose) < 1e-6)
        // A nearby target (Saturn ~15° away), even with the tube rolled: small residual.
        assertTrue("near ${errorAfter(cal, pose(155.0, 42.0, spin = 20.0))}", errorAfter(cal, pose(155.0, 42.0, spin = 20.0)) < 1.5)
    }

    @Test fun twoSyncsAtDifferentAltitudesFixTheWholeSky() {
        val cal = TelescopeSync.calibrate(listOf(sightingAt(pose(140.0, 25.0)), sightingAt(pose(250.0, 65.0, spin = 40.0), "Vega")))!!
        assertTrue(cal.headingSolved)
        assertEquals(compassError, cal.headingCorrectionDeg, 0.1)
        listOf(pose(10.0, 50.0), pose(300.0, 15.0, spin = 90.0), pose(200.0, 75.0)).forEach {
            assertTrue("far ${errorAfter(cal, it)}", errorAfter(cal, it) < 0.1)
        }
    }

    @Test fun disagreementFlagsAMovedPhone() {
        val cal = TelescopeSync.calibrate(listOf(sightingAt(pose(140.0, 35.0))))!!
        val ok = sightingAt(pose(150.0, 40.0))
        assertTrue(TelescopeSync.disagreementDeg(cal, ok) < 1.5)
        val moved = Sighting(pose(150.0, 52.0), ok.target, "Saturn", 0.0) // same target, phone tilted 12° differently
        assertTrue(TelescopeSync.disagreementDeg(cal, moved) > 5)
    }

    @Test fun storedSightingRoundTrip() {
        val s = sightingAt(pose(80.0, 30.0))
        val stored = StoredSighting.from(s, 123L, HeadingSource.COMPASS)
        val back = StoredSighting.listFromJson(StoredSighting.listToJson(listOf(stored))).single()
        assertEquals(stored, back)
        assertTrue(back.toSighting().rotation.contentEquals(s.rotation))
        assertTrue(StoredSighting.listFromJson("garbage").isEmpty())
    }

    @Test fun celestialObjectsMoveAndRatesAreRealistic() {
        val calc = SkyCalculator()
        val loc = GeoLocation(28.3, -16.5)
        val t = Instant.parse("2025-01-10T21:30:00Z")
        val moon = calc.motion(Catalog.moon, loc, t)
        val jupiter = calc.motion(Catalog.byId("jupiter")!!, loc, t)
        // Sidereal rotation moves objects by at most 0.25°/min (15°/h × cos δ); the Moon a bit less.
        assertTrue("moon ${moon.totalDegPerMin}", moon.totalDegPerMin in 0.05..0.26)
        assertTrue("jupiter ${jupiter.totalDegPerMin}", jupiter.totalDegPerMin in 0.05..0.26)
        val p0 = calc.position(Catalog.byId("jupiter")!!, loc, t)
        val p10 = calc.position(Catalog.byId("jupiter")!!, loc, t.plusSeconds(600))
        val moved = SkyCalculator.angularSeparation(p0.altitudeDeg, p0.azimuthDeg, p10.altitudeDeg, p10.azimuthDeg)
        assertEquals(jupiter.totalDegPerMin * 10, moved, 0.2)
        // Polaris barely moves.
        assertTrue(calc.motion(Catalog.byId("polaris")!!, loc, t).totalDegPerMin < 0.01)
    }
}

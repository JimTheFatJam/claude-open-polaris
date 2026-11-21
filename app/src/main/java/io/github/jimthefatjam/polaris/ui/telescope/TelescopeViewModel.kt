package io.github.jimthefatjam.polaris.ui.telescope

import android.hardware.GeomagneticField
import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jimthefatjam.polaris.core.astro.Catalog
import io.github.jimthefatjam.polaris.core.astro.SkyCalculator
import io.github.jimthefatjam.polaris.core.model.CelestialObject
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.ObjectType
import io.github.jimthefatjam.polaris.core.telescope.HeadingSource
import io.github.jimthefatjam.polaris.core.telescope.HorizontalDirection
import io.github.jimthefatjam.polaris.core.telescope.MountingCalibration
import io.github.jimthefatjam.polaris.core.telescope.PhoneGuidance
import io.github.jimthefatjam.polaris.core.telescope.PlaneGuide
import io.github.jimthefatjam.polaris.core.telescope.PointingMath
import io.github.jimthefatjam.polaris.core.telescope.PushToGuide
import io.github.jimthefatjam.polaris.data.ObservingRepository
import io.github.jimthefatjam.polaris.data.OrientationReading
import io.github.jimthefatjam.polaris.data.OrientationSensor
import io.github.jimthefatjam.polaris.data.OrientationSource
import io.github.jimthefatjam.polaris.data.PointingMode
import io.github.jimthefatjam.polaris.data.SensorCapabilities
import io.github.jimthefatjam.polaris.data.SettingsRepository
import io.github.jimthefatjam.polaris.data.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class TrackedObject(val obj: CelestialObject, val direction: HorizontalDirection)

data class TelescopeUi(
    val capabilities: SensorCapabilities,
    val hasReading: Boolean = false,
    val mode: PointingMode = PointingMode.PLANE,
    val headingSource: HeadingSource = HeadingSource.COMPASS,
    /** Camera mode: where the back of the phone points (true az/alt). */
    val camera: HorizontalDirection? = null,
    val cameraGuide: PushToGuide? = null,
    /** Plane mode: the direction the screen faces (plane normal). */
    val planeNormal: HorizontalDirection? = null,
    val planeGuide: PlaneGuide? = null,
    /** Target's altitude-only offset from the plane is valid without heading; azimuth needs a heading. */
    val headingValid: Boolean = true,
    val declinationDeg: Double? = null,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val magneticFieldUt: Double? = null,
    val location: GeoLocation? = null,
    val candidates: List<TrackedObject> = emptyList(),
    val target: TrackedObject? = null,
    val syncObject: TrackedObject? = null,
    val sessionHeadingDeg: Double? = null,
    val lastSyncAt: Instant? = null,
    val syncAltitudeErrorDeg: Double? = null,
    val recording: Boolean = false,
    val message: String? = null,
    val now: Instant = Instant.now(),
) {
    /** Earth's field is roughly 25–65 µT; outside that range something nearby is distorting it. */
    val magneticInterference: Boolean get() = magneticFieldUt?.let { it < 20 || it > 70 } ?: false
    val minutesSinceSync: Long? get() = lastSyncAt?.let { Duration.between(it, now).toMinutes() }
    val syncStale: Boolean get() = headingSource == HeadingSource.GYRO && (minutesSinceSync ?: 0) >= 15
}

private data class Controls(
    val targetId: String? = null,
    val syncId: String? = null,
    val sessionHeadingDeg: Double? = null,
    val lastSyncAt: Instant? = null,
    val syncAltitudeErrorDeg: Double? = null,
    val recording: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TelescopeViewModel(
    private val sensor: OrientationSensor,
    private val settings: SettingsRepository,
    private val observing: ObservingRepository,
) : ViewModel() {
    private val sky = SkyCalculator()
    private val controls = MutableStateFlow(Controls())

    /** Low-pass filtered rotation matrix (re-orthonormalised each step). */
    private var smoothed: DoubleArray? = null
    private val smoothing = 0.15

    /** Recent raw readings, used to average a still burst when syncing. */
    private val buffer = ArrayDeque<Pair<Long, DoubleArray>>()
    private val bufferLock = Any()

    private val ticker = flow { while (true) { emit(Instant.now()); delay(2_000) } }

    private val skyPositions = combine(observing.state, ticker) { o, now ->
        val loc = o.location
        val list = if (loc == null) emptyList()
        else Catalog.all.filter { it.type != ObjectType.SUN }.map { TrackedObject(it, dir(it, loc, now)) }
            .filter { it.direction.altitudeDeg > 0 }
        Triple(loc, list, now)
    }

    private val readings = settings.settings.map { useGyro(it) }.distinctUntilChanged().flatMapLatest { gyro ->
        smoothed = null
        sensor.readings(gyroHeading = gyro)
            .onEach { r ->
                synchronized(bufferLock) {
                    buffer.addLast(System.nanoTime() to r.matrix)
                    while (buffer.size > 200) buffer.removeFirst()
                }
            }
            .map<OrientationReading, OrientationReading?> { it }
            .onStart { emit(null) }
    }

    val state: StateFlow<TelescopeUi> = combine(readings, settings.settings, controls, skyPositions) { reading, s, c, (loc, positions, now) ->
        build(reading, s, c, loc, positions, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), TelescopeUi(sensor.capabilities))

    private fun useGyro(s: UserSettings) = s.headingSource == HeadingSource.GYRO && sensor.capabilities.gameRotationVector

    private fun smooth(m: DoubleArray): DoubleArray {
        val prev = smoothed
        val next = if (prev == null) m.copyOf() else MountingCalibration.orthonormalize(DoubleArray(9) { prev[it] + smoothing * (m[it] - prev[it]) })
        smoothed = next
        return next
    }

    private fun build(
        reading: OrientationReading?, s: UserSettings, c: Controls, loc: GeoLocation?, positions: List<TrackedObject>, now: Instant,
    ): TelescopeUi {
        val gyro = useGyro(s)
        val declination = loc?.let { declinationAt(it) }
        // In gyro mode the sensor's "north" is arbitrary: only a star sync gives absolute heading.
        val decl = if (gyro) 0.0 else declination ?: 0.0
        val beta = c.sessionHeadingDeg ?: 0.0
        val headingValid = !gyro || c.sessionHeadingDeg != null
        val find = { id: String? -> positions.firstOrNull { it.obj.id == id } }
        val target = find(c.targetId)

        var camera: HorizontalDirection? = null
        var cameraGuide: PushToGuide? = null
        var normal: HorizontalDirection? = null
        var planeGuide: PlaneGuide? = null
        if (reading != null) {
            val r = smooth(reading.matrix)
            when (s.pointingMode) {
                PointingMode.CAMERA -> {
                    camera = PhoneGuidance.cameraPointing(r, decl, beta)
                    if (target != null && headingValid) cameraGuide = PointingMath.guide(camera, target.direction)
                }
                PointingMode.PLANE -> {
                    normal = PhoneGuidance.planeNormal(r, decl, beta)
                    if (target != null && headingValid) planeGuide = PhoneGuidance.planeGuide(r, target.direction, decl, beta)
                }
            }
        }
        val bright = Catalog.alignmentCandidates.map { it.id }.toSet()
        return TelescopeUi(
            capabilities = sensor.capabilities,
            hasReading = reading != null,
            mode = s.pointingMode,
            headingSource = if (gyro) HeadingSource.GYRO else HeadingSource.COMPASS,
            camera = camera,
            cameraGuide = cameraGuide,
            planeNormal = normal,
            planeGuide = planeGuide,
            headingValid = headingValid,
            declinationDeg = declination,
            accuracy = reading?.accuracy ?: SensorManager.SENSOR_STATUS_UNRELIABLE,
            magneticFieldUt = reading?.magneticFieldUt,
            location = loc,
            candidates = positions.sortedByDescending { if (it.obj.id in bright) 1 else 0 },
            target = target,
            syncObject = find(c.syncId),
            sessionHeadingDeg = c.sessionHeadingDeg,
            lastSyncAt = c.lastSyncAt,
            syncAltitudeErrorDeg = c.syncAltitudeErrorDeg,
            recording = c.recording,
            message = c.message,
            now = now,
        )
    }

    private fun dir(o: CelestialObject, loc: GeoLocation, now: Instant): HorizontalDirection {
        val p = sky.position(o, loc, now)
        return HorizontalDirection(p.azimuthDeg, p.altitudeDeg)
    }

    private fun declinationAt(loc: GeoLocation) =
        GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.elevationMeters.toFloat(), System.currentTimeMillis()).declination.toDouble()

    fun setMode(m: PointingMode) = viewModelScope.launch { settings.setPointingMode(m) }

    fun setHeadingSource(h: HeadingSource) = viewModelScope.launch {
        settings.setHeadingSource(h)
        synchronized(bufferLock) { buffer.clear() }
        // A different heading reference invalidates the session sync.
        controls.update { it.copy(sessionHeadingDeg = null, lastSyncAt = null, syncAltitudeErrorDeg = null, message = null) }
    }

    fun chooseTarget(id: String?) = controls.update { it.copy(targetId = id) }
    fun chooseSyncObject(id: String?) = controls.update { it.copy(syncId = id, message = null) }
    fun clearSync() = controls.update { it.copy(sessionHeadingDeg = null, lastSyncAt = null, syncAltitudeErrorDeg = null, message = null) }

    /**
     * Star sync (camera style): aim the back of the phone at the chosen object, hold still, and the
     * heading correction for this session is measured. It applies to both navigation modes.
     */
    fun syncNow() {
        if (controls.value.recording) return
        viewModelScope.launch {
            controls.update { it.copy(recording = true, message = "Hold still…") }
            val start = System.nanoTime()
            delay(1_500)
            val samples = synchronized(bufferLock) { buffer.filter { it.first >= start }.map { it.second } }
            val s = state.value
            val msg = when {
                samples.size < 5 -> "Not enough sensor data — is the screen on?"
                s.syncObject == null -> "Choose an object to sync on."
                else -> {
                    val (avg, spread) = MountingCalibration.average(samples)
                    if (spread > 0.6) "The phone moved during the measurement (%.1f°). Hold it still and try again.".format(spread)
                    else {
                        val decl = if (s.headingSource == HeadingSource.GYRO) 0.0 else s.declinationDeg ?: 0.0
                        val sync = PhoneGuidance.cameraSync(avg, s.syncObject.direction, decl)
                        smoothed = null
                        controls.update { it.copy(sessionHeadingDeg = sync.headingCorrectionDeg, lastSyncAt = Instant.now(), syncAltitudeErrorDeg = sync.altitudeErrorDeg) }
                        if (sync.mountMayHaveShifted) "Synced, but altitude is off by %.1f° — was the back of the phone aimed accurately?".format(sync.altitudeErrorDeg)
                        else "Synced on ${s.syncObject.obj.name}: heading correction %+.1f°.".format(sync.headingCorrectionDeg)
                    }
                }
            }
            controls.update { it.copy(recording = false, message = msg) }
        }
    }

    val sourceLabel: String get() = sensor.capabilities.source.label
    val unsupported: Boolean get() = sensor.capabilities.source == OrientationSource.NONE
}

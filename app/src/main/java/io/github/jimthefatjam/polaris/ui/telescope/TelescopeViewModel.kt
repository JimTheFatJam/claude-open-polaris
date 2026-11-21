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
import io.github.jimthefatjam.polaris.core.telescope.CalibrationResult
import io.github.jimthefatjam.polaris.core.telescope.HeadingSource
import io.github.jimthefatjam.polaris.core.telescope.HorizontalDirection
import io.github.jimthefatjam.polaris.core.telescope.MountingCalibration
import io.github.jimthefatjam.polaris.core.telescope.PhoneGuidance
import io.github.jimthefatjam.polaris.core.telescope.PlaneGuide
import io.github.jimthefatjam.polaris.core.telescope.PointingMath
import io.github.jimthefatjam.polaris.core.telescope.PushToGuide
import io.github.jimthefatjam.polaris.core.telescope.Sighting
import io.github.jimthefatjam.polaris.core.telescope.StoredSighting
import io.github.jimthefatjam.polaris.core.telescope.TelescopeSync
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
import kotlinx.coroutines.flow.first
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

/** One telescope sync as shown in the UI. */
data class SyncUi(val name: String, val time: Instant, val residualDeg: Double?)

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
    /** Plane mode after a telescope sync: the telescope's line of sight and the guide to the target. */
    val scopePointing: HorizontalDirection? = null,
    val scopeGuide: PushToGuide? = null,
    val headingValid: Boolean = true,
    val declinationDeg: Double? = null,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val magneticFieldUt: Double? = null,
    val location: GeoLocation? = null,
    val candidates: List<TrackedObject> = emptyList(),
    val target: TrackedObject? = null,
    /** Live apparent motion of the target. */
    val targetMotion: SkyCalculator.Motion? = null,
    val syncObject: TrackedObject? = null,
    /** Camera-style heading sync (session). */
    val sessionHeadingDeg: Double? = null,
    val lastSyncAt: Instant? = null,
    val syncAltitudeErrorDeg: Double? = null,
    /** Telescope line-of-sight sync. */
    val telescopeSyncs: List<SyncUi> = emptyList(),
    val telescopeCalibration: CalibrationResult? = null,
    val recording: Boolean = false,
    val message: String? = null,
    val now: Instant = Instant.now(),
) {
    val magneticInterference: Boolean get() = magneticFieldUt?.let { it < 20 || it > 70 } ?: false
    val minutesSinceSync: Long? get() = lastSyncAt?.let { Duration.between(it, now).toMinutes() }
    val syncStale: Boolean get() = headingSource == HeadingSource.GYRO && (minutesSinceSync ?: 0) >= 15
    val telescopeSynced: Boolean get() = telescopeCalibration != null
    /** Time since the most recent telescope sync. */
    val minutesSinceTelescopeSync: Long? get() = telescopeSyncs.maxOfOrNull { it.time }?.let { Duration.between(it, now).toMinutes() }
}

private data class Controls(
    val targetId: String? = null,
    val syncId: String? = null,
    val sessionHeadingDeg: Double? = null,
    val lastSyncAt: Instant? = null,
    val syncAltitudeErrorDeg: Double? = null,
    /** Gyro-mode telescope syncs live only for this session (gyro heading restarts arbitrarily). */
    val gyroSyncs: List<StoredSighting> = emptyList(),
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

    private var smoothed: DoubleArray? = null
    private val smoothing = 0.15

    private val buffer = ArrayDeque<Pair<Long, DoubleArray>>()
    private val bufferLock = Any()

    // Caches so the (relatively expensive) fit and motion aren't recomputed on every sensor frame.
    private var calKey: List<StoredSighting>? = null
    private var calValue: CalibrationResult? = null
    private var motionKey: Pair<String, Instant>? = null
    private var motionValue: SkyCalculator.Motion? = null

    /** Positions refresh every second, so moving objects are tracked live. */
    private val ticker = flow { while (true) { emit(Instant.now()); delay(1_000) } }

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

    private fun activeSyncs(s: UserSettings, c: Controls): List<StoredSighting> =
        if (useGyro(s)) c.gyroSyncs else s.telescopeSyncs.filter { it.headingSource == HeadingSource.COMPASS.name }

    private fun calibration(syncs: List<StoredSighting>): CalibrationResult? {
        if (syncs != calKey) {
            calKey = syncs
            calValue = TelescopeSync.calibrate(syncs.map { it.toSighting() })
        }
        return calValue
    }

    private fun motion(t: TrackedObject?, loc: GeoLocation?, now: Instant): SkyCalculator.Motion? {
        if (t == null || loc == null) return null
        val key = t.obj.id to now
        if (key != motionKey) { motionKey = key; motionValue = sky.motion(t.obj, loc, now) }
        return motionValue
    }

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
        val decl = if (gyro) 0.0 else declination ?: 0.0
        val beta = c.sessionHeadingDeg ?: 0.0
        val syncs = activeSyncs(s, c)
        val cal = calibration(syncs)
        val find = { id: String? -> positions.firstOrNull { it.obj.id == id } }
        val target = find(c.targetId)
        val headingValid = when (s.pointingMode) {
            PointingMode.CAMERA -> !gyro || c.sessionHeadingDeg != null
            PointingMode.PLANE -> !gyro || cal != null || c.sessionHeadingDeg != null
        }

        var camera: HorizontalDirection? = null
        var cameraGuide: PushToGuide? = null
        var normal: HorizontalDirection? = null
        var planeGuide: PlaneGuide? = null
        var scope: HorizontalDirection? = null
        var scopeGuide: PushToGuide? = null
        if (reading != null) {
            val r = smooth(reading.matrix)
            when (s.pointingMode) {
                PointingMode.CAMERA -> {
                    camera = PhoneGuidance.cameraPointing(r, decl, beta)
                    if (target != null && headingValid) cameraGuide = PointingMath.guide(camera, target.direction)
                }
                PointingMode.PLANE -> {
                    normal = PhoneGuidance.planeNormal(r, decl, beta)
                    if (cal != null) {
                        // Synced: follow the telescope's measured line of sight instead of the plane's vertical line.
                        scope = MountingCalibration.pointing(r, cal.axis, decl, cal.headingCorrectionDeg)
                        if (target != null) scopeGuide = PointingMath.guide(scope, target.direction)
                    }
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
            scopePointing = scope,
            scopeGuide = scopeGuide,
            headingValid = headingValid,
            declinationDeg = declination,
            accuracy = reading?.accuracy ?: SensorManager.SENSOR_STATUS_UNRELIABLE,
            magneticFieldUt = reading?.magneticFieldUt,
            location = loc,
            candidates = positions.sortedByDescending { if (it.obj.id in bright) 1 else 0 },
            target = target,
            targetMotion = motion(target, loc, now),
            syncObject = find(c.syncId) ?: target,
            sessionHeadingDeg = c.sessionHeadingDeg,
            lastSyncAt = c.lastSyncAt,
            syncAltitudeErrorDeg = c.syncAltitudeErrorDeg,
            telescopeSyncs = syncs.mapIndexed { i, st -> SyncUi(st.name, Instant.ofEpochMilli(st.timeEpochMs), cal?.residualsDeg?.getOrNull(i)) },
            telescopeCalibration = cal,
            recording = c.recording,
            message = c.message,
            now = now,
        )
    }

    private fun dir(o: CelestialObject, loc: GeoLocation, at: Instant): HorizontalDirection {
        val p = sky.position(o, loc, at)
        return HorizontalDirection(p.azimuthDeg, p.altitudeDeg)
    }

    private fun declinationAt(loc: GeoLocation) =
        GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.elevationMeters.toFloat(), System.currentTimeMillis()).declination.toDouble()

    fun setMode(m: PointingMode) = viewModelScope.launch { settings.setPointingMode(m) }

    fun setHeadingSource(h: HeadingSource) = viewModelScope.launch {
        settings.setHeadingSource(h)
        synchronized(bufferLock) { buffer.clear() }
        controls.update { it.copy(sessionHeadingDeg = null, lastSyncAt = null, syncAltitudeErrorDeg = null, gyroSyncs = emptyList(), message = null) }
    }

    fun chooseTarget(id: String?) = controls.update { it.copy(targetId = id) }
    fun chooseSyncObject(id: String?) = controls.update { it.copy(syncId = id, message = null) }
    fun clearSync() = controls.update { it.copy(sessionHeadingDeg = null, lastSyncAt = null, syncAltitudeErrorDeg = null, message = null) }

    /** Camera-style heading sync: the back of the phone aimed at a known object. */
    fun syncNow() = captureStill { avg, s, obj, at ->
        val decl = if (s.headingSource == HeadingSource.GYRO) 0.0 else s.declinationDeg ?: 0.0
        val sync = PhoneGuidance.cameraSync(avg, at, decl)
        smoothed = null
        controls.update { it.copy(sessionHeadingDeg = sync.headingCorrectionDeg, lastSyncAt = Instant.now(), syncAltitudeErrorDeg = sync.altitudeErrorDeg) }
        if (sync.mountMayHaveShifted) "Synced, but altitude is off by %.1f° — was the back of the phone aimed accurately?".format(sync.altitudeErrorDeg)
        else "Synced on ${obj.name}: heading correction %+.1f°.".format(sync.headingCorrectionDeg)
    }

    /**
     * Telescope line-of-sight sync: the chosen object is centred in the telescope (or in the phone
     * camera looking through it). Adds a sync; if it disagrees strongly with earlier syncs the phone
     * probably moved on the tube, so earlier syncs are replaced.
     */
    fun telescopeSync() = captureStill { avg, s, obj, at ->
        val gyro = s.headingSource == HeadingSource.GYRO
        val decl = if (gyro) 0.0 else s.declinationDeg ?: 0.0
        val sighting = Sighting(avg, at, obj.name, decl)
        val stored = StoredSighting.from(sighting, System.currentTimeMillis(), s.headingSource)
        val current = activeSyncs(settings.settings.first(), controls.value)
        val existing = s.telescopeCalibration
        val disagreement = existing?.let { TelescopeSync.disagreementDeg(it, sighting) }
        val replace = disagreement != null && disagreement > 5.0
        val next = (if (replace) emptyList() else current).plus(stored).takeLast(TelescopeSync.MAX_SYNCS)
        if (gyro) controls.update { it.copy(gyroSyncs = next) } else settings.setTelescopeSyncs(next)
        smoothed = null
        when {
            replace -> "This sync was %.1f° off from the earlier ones — the phone probably moved on the telescope, so earlier syncs were replaced.".format(disagreement)
            next.size == 1 -> "Synced on ${obj.name}. Pointing now follows the telescope's line of sight. Sync a second object at a different altitude to correct the whole sky."
            else -> "Added sync on ${obj.name} (${next.size} syncs)" + (disagreement?.let { " — it agreed within %.1f°.".format(it) } ?: ".")
        }
    }

    fun resetTelescopeSync() = viewModelScope.launch {
        settings.setTelescopeSyncs(emptyList())
        controls.update { it.copy(gyroSyncs = emptyList(), message = "Telescope sync cleared.") }
    }

    /**
     * Collects 1.5 s of readings, rejects the burst if the phone moved more than 0.6°, then calls
     * [onStill] with the averaged rotation and the object's position computed at the middle of the
     * burst (objects move up to 0.25°/min, so a stale position would bias the sync).
     */
    private fun captureStill(onStill: suspend (DoubleArray, TelescopeUi, CelestialObject, HorizontalDirection) -> String?) {
        if (controls.value.recording) return
        viewModelScope.launch {
            controls.update { it.copy(recording = true, message = "Hold still…") }
            val start = System.nanoTime()
            val mid = Instant.now().plusMillis(750)
            delay(1_500)
            val samples = synchronized(bufferLock) { buffer.filter { it.first >= start }.map { it.second } }
            val s = state.value
            val obj = s.syncObject?.obj
            val loc = s.location
            val msg = when {
                samples.size < 5 -> "Not enough sensor data — is the screen on?"
                obj == null || loc == null -> "Choose an object to sync on."
                else -> {
                    val (avg, spread) = MountingCalibration.average(samples)
                    if (spread > 0.6) "The phone moved during the measurement (%.1f°). Hold it still and try again.".format(spread)
                    else onStill(avg, s, obj, dir(obj, loc, mid))
                }
            }
            controls.update { it.copy(recording = false, message = msg) }
        }
    }

    val sourceLabel: String get() = sensor.capabilities.source.label
    val unsupported: Boolean get() = sensor.capabilities.source == OrientationSource.NONE
}

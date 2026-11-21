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
import io.github.jimthefatjam.polaris.core.telescope.AlignmentOffset
import io.github.jimthefatjam.polaris.core.telescope.CalibrationResult
import io.github.jimthefatjam.polaris.core.telescope.DeviceAxis
import io.github.jimthefatjam.polaris.core.telescope.DirectionSmoother
import io.github.jimthefatjam.polaris.core.telescope.HeadingSource
import io.github.jimthefatjam.polaris.core.telescope.HorizontalDirection
import io.github.jimthefatjam.polaris.core.telescope.MountingCalibration
import io.github.jimthefatjam.polaris.core.telescope.MountingProfile
import io.github.jimthefatjam.polaris.core.telescope.PointingMath
import io.github.jimthefatjam.polaris.core.telescope.PushToGuide
import io.github.jimthefatjam.polaris.core.telescope.Sighting
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
import java.util.UUID

data class TrackedObject(val obj: CelestialObject, val direction: HorizontalDirection)

data class SightingUi(val name: String, val target: HorizontalDirection, val residualDeg: Double?)

data class CalibrationUi(
    val sightings: List<SightingUi> = emptyList(),
    val result: CalibrationResult? = null,
    val recording: Boolean = false,
    val message: String? = null,
)

data class TelescopeUi(
    val capabilities: SensorCapabilities,
    val hasReading: Boolean = false,
    val pointingMode: PointingMode = PointingMode.SIMPLE,
    val headingSource: HeadingSource = HeadingSource.COMPASS,
    val axis: DeviceAxis = DeviceAxis.TOP_EDGE,
    /** Simple mode: raw pointing relative to magnetic north. */
    val magnetic: HorizontalDirection? = null,
    /** Simple mode: after magnetic-declination correction (true north). */
    val trueNorth: HorizontalDirection? = null,
    /** Best estimate of where the tube points (either mode). */
    val estimated: HorizontalDirection? = null,
    /** False when azimuth is not meaningful yet (gyro heading without a sync). */
    val azimuthValid: Boolean = true,
    val declinationDeg: Double? = null,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val magneticFieldUt: Double? = null,
    val alignment: AlignmentOffset = AlignmentOffset(),
    val location: GeoLocation? = null,
    val candidates: List<TrackedObject> = emptyList(),
    val reference: TrackedObject? = null,
    val target: TrackedObject? = null,
    val guide: PushToGuide? = null,
    // Profile mode
    val profiles: List<MountingProfile> = emptyList(),
    val activeProfile: MountingProfile? = null,
    val sessionHeadingDeg: Double? = null,
    val lastSyncAt: Instant? = null,
    val syncAltitudeErrorDeg: Double? = null,
    val calibrationTarget: TrackedObject? = null,
    val calibration: CalibrationUi = CalibrationUi(),
    val now: Instant = Instant.now(),
) {
    /** Earth's field is roughly 25–65 µT; outside that range something nearby is distorting it. */
    val magneticInterference: Boolean get() = magneticFieldUt?.let { it < 20 || it > 70 } ?: false
    val minutesSinceSync: Long? get() = lastSyncAt?.let { Duration.between(it, now).toMinutes() }
    val syncStale: Boolean get() = headingSource == HeadingSource.GYRO && (minutesSinceSync ?: 0) >= 15
}

private data class Controls(
    val alignment: AlignmentOffset = AlignmentOffset(),
    val referenceId: String? = null,
    val targetId: String? = null,
    val sessionHeadingDeg: Double? = null,
    val lastSyncAt: Instant? = null,
    val syncAltitudeErrorDeg: Double? = null,
    val calibrationTargetId: String? = null,
    val sightings: List<Sighting> = emptyList(),
    val result: CalibrationResult? = null,
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
    private val simpleSmoother = DirectionSmoother(alpha = 0.12)
    private val profileSmoother = DirectionSmoother(alpha = 0.12)
    private val controls = MutableStateFlow(Controls())

    /** Recent raw readings, used to average a still burst when recording a sighting or syncing. */
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

    private fun build(
        reading: OrientationReading?, s: UserSettings, c: Controls, loc: GeoLocation?, positions: List<TrackedObject>, now: Instant,
    ): TelescopeUi {
        val declination = loc?.let { declinationAt(it) }
        val find = { id: String? -> positions.firstOrNull { it.obj.id == id } }
        val target = find(c.targetId)
        val profile = s.activeProfile
        val gyro = useGyro(s)

        var magnetic: HorizontalDirection? = null
        var trueDir: HorizontalDirection? = null
        var estimated: HorizontalDirection? = null
        var azimuthValid = true
        if (reading != null) {
            if (s.pointingMode == PointingMode.PROFILE && profile != null) {
                val decl = if (gyro) 0.0 else declination ?: 0.0
                val raw = MountingCalibration.pointing(reading.matrix, profile.axis, decl, c.sessionHeadingDeg ?: 0.0)
                estimated = profileSmoother.update(raw)
                azimuthValid = !gyro || c.sessionHeadingDeg != null
            } else {
                magnetic = simpleSmoother.update(PointingMath.pointing(reading.matrix, s.deviceAxis))
                trueDir = HorizontalDirection(PointingMath.magneticToTrue(magnetic.azimuthDeg, declination ?: 0.0), magnetic.altitudeDeg)
                estimated = c.alignment.apply(trueDir)
            }
        }
        val alignable = Catalog.alignmentCandidates.map { it.id }.toSet()
        val residuals = c.result?.residualsDeg
        return TelescopeUi(
            capabilities = sensor.capabilities,
            hasReading = reading != null,
            pointingMode = s.pointingMode,
            headingSource = if (gyro) HeadingSource.GYRO else HeadingSource.COMPASS,
            axis = s.deviceAxis,
            magnetic = magnetic,
            trueNorth = trueDir,
            estimated = estimated,
            azimuthValid = azimuthValid,
            declinationDeg = declination,
            accuracy = reading?.accuracy ?: SensorManager.SENSOR_STATUS_UNRELIABLE,
            magneticFieldUt = reading?.magneticFieldUt,
            alignment = c.alignment,
            location = loc,
            candidates = positions.sortedByDescending { if (it.obj.id in alignable) 1 else 0 },
            reference = find(c.referenceId),
            target = target,
            guide = if (estimated != null && target != null && azimuthValid) PointingMath.guide(estimated, target.direction) else null,
            profiles = s.mountingProfiles.sortedByDescending { it.createdAtEpochMs },
            activeProfile = profile,
            sessionHeadingDeg = c.sessionHeadingDeg,
            lastSyncAt = c.lastSyncAt,
            syncAltitudeErrorDeg = c.syncAltitudeErrorDeg,
            calibrationTarget = find(c.calibrationTargetId),
            calibration = CalibrationUi(
                sightings = c.sightings.mapIndexed { i, sg -> SightingUi(sg.targetName, sg.target, residuals?.getOrNull(i)) },
                result = c.result,
                recording = c.recording,
                message = c.message,
            ),
            now = now,
        )
    }

    /** Gyro-only heading applies to profile mode; Simple mode always uses the compass (v0.1.0 behaviour). */
    private fun useGyro(s: UserSettings) =
        s.pointingMode == PointingMode.PROFILE && s.headingSource == HeadingSource.GYRO && sensor.capabilities.gameRotationVector

    private fun dir(o: CelestialObject, loc: GeoLocation, now: Instant): HorizontalDirection {
        val p = sky.position(o, loc, now)
        return HorizontalDirection(p.azimuthDeg, p.altitudeDeg)
    }

    private fun declinationAt(loc: GeoLocation) =
        GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.elevationMeters.toFloat(), System.currentTimeMillis()).declination.toDouble()

    // ------------------------------------------------------------------ Simple mode (v0.1.0)

    fun setAxis(a: DeviceAxis) {
        viewModelScope.launch { settings.setDeviceAxis(a) }
        simpleSmoother.reset()
        controls.update { it.copy(alignment = AlignmentOffset()) } // mounting changed: alignment no longer valid
    }

    fun chooseReference(id: String?) = controls.update { it.copy(referenceId = id) }
    fun chooseTarget(id: String?) = controls.update { it.copy(targetId = id) }

    fun alignNow() {
        val s = state.value
        val measured = s.trueNorth ?: return
        val ref = s.reference ?: return
        controls.update { it.copy(alignment = AlignmentOffset.from(measured, ref.direction, ref.obj.name)) }
    }

    fun resetAlignment() = controls.update { it.copy(alignment = AlignmentOffset()) }

    // ------------------------------------------------------------------ Mode & heading source

    fun setPointingMode(m: PointingMode) = viewModelScope.launch { settings.setPointingMode(m); profileSmoother.reset() }

    fun setHeadingSource(h: HeadingSource) = viewModelScope.launch {
        settings.setHeadingSource(h)
        synchronized(bufferLock) { buffer.clear() }
        simpleSmoother.reset(); profileSmoother.reset()
        // The heading reference changed, so any session sync and pending sightings are invalid.
        controls.update { it.copy(sessionHeadingDeg = null, lastSyncAt = null, syncAltitudeErrorDeg = null, sightings = emptyList(), result = null,
            message = if (it.sightings.isNotEmpty()) "Heading source changed — calibration sightings cleared." else null) }
    }

    // ------------------------------------------------------------------ Calibration & profiles

    fun chooseCalibrationTarget(id: String?) = controls.update { it.copy(calibrationTargetId = id, message = null) }

    /** Averages ~1.5 s of readings while the telescope is held on the calibration target. */
    fun recordSighting() = captureStill { avg, s ->
        val t = s.calibrationTarget ?: return@captureStill "Choose an object first."
        val decl = if (s.headingSource == HeadingSource.GYRO) 0.0 else s.declinationDeg ?: 0.0
        val warn = if (s.headingSource == HeadingSource.COMPASS && s.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
            " Compass accuracy is low — consider a figure-8 calibration." else ""
        controls.update {
            val list = it.sightings + Sighting(avg, t.direction, t.obj.name, decl)
            it.copy(sightings = list, result = null, message = "Recorded ${t.obj.name} (${list.size} sighting${if (list.size == 1) "" else "s"}).$warn")
        }
        null
    }

    fun removeSighting(index: Int) = controls.update {
        it.copy(sightings = it.sightings.filterIndexed { i, _ -> i != index }, result = null, message = null)
    }

    fun clearCalibration() = controls.update { it.copy(sightings = emptyList(), result = null, message = null) }

    fun computeCalibration() {
        val c = controls.value
        if (c.sightings.isEmpty()) return
        val result = MountingCalibration.solve(c.sightings, solveHeading = true)
        if (state.value.headingSource == HeadingSource.GYRO && !result.headingSolved) {
            controls.update { it.copy(result = null, message = "In gyro mode, heading must be solved: record at least two objects at clearly different altitudes.") }
            return
        }
        // The heading correction found during calibration is valid for this session: use it as a sync.
        controls.update {
            it.copy(result = result, message = null,
                sessionHeadingDeg = if (result.headingSolved) result.headingCorrectionDeg else it.sessionHeadingDeg,
                lastSyncAt = if (result.headingSolved) Instant.now() else it.lastSyncAt)
        }
    }

    fun saveProfile(name: String) {
        val c = controls.value
        val result = c.result ?: return
        val profile = MountingProfile.from(result, UUID.randomUUID().toString(), name.ifBlank { "Mounting ${state.value.profiles.size + 1}" },
            c.sightings.size, System.currentTimeMillis(), state.value.headingSource)
        viewModelScope.launch {
            settings.saveProfile(profile)
            profileSmoother.reset()
            controls.update { it.copy(sightings = emptyList(), result = null, message = "Saved “${profile.name}” and made it active.") }
        }
    }

    fun selectProfile(id: String) = viewModelScope.launch {
        settings.setActiveProfile(id)
        settings.setPointingMode(PointingMode.PROFILE)
        profileSmoother.reset()
        controls.update { it.copy(sessionHeadingDeg = null, lastSyncAt = null, syncAltitudeErrorDeg = null) }
    }

    fun deleteProfile(id: String) = viewModelScope.launch { settings.deleteProfile(id) }

    /** One-star session sync: measures today's heading correction for the active profile. */
    fun syncNow() = captureStill { avg, s ->
        val profile = s.activeProfile ?: return@captureStill "No active profile."
        val t = s.reference ?: return@captureStill "Choose an object to sync on."
        val decl = if (s.headingSource == HeadingSource.GYRO) 0.0 else s.declinationDeg ?: 0.0
        val sync = MountingCalibration.sync(avg, profile.axis, t.direction, decl)
        profileSmoother.reset()
        controls.update {
            it.copy(sessionHeadingDeg = sync.headingCorrectionDeg, lastSyncAt = Instant.now(), syncAltitudeErrorDeg = sync.altitudeErrorDeg,
                message = if (sync.mountMayHaveShifted) "Synced, but altitude is off by %.1f° — the phone may have moved on its mount. Consider re-calibrating.".format(sync.altitudeErrorDeg)
                else "Synced on ${t.obj.name}: heading correction %+.1f°.".format(sync.headingCorrectionDeg))
        }
        null
    }

    fun clearSync() = controls.update { it.copy(sessionHeadingDeg = null, lastSyncAt = null, syncAltitudeErrorDeg = null) }

    /**
     * Collects readings for 1.5 s, rejects the burst if the phone moved more than 0.6°, then hands the
     * averaged rotation to [onStill]. [onStill] returns an error message or null on success.
     */
    private fun captureStill(onStill: (DoubleArray, TelescopeUi) -> String?) {
        if (controls.value.recording) return
        viewModelScope.launch {
            controls.update { it.copy(recording = true, message = "Hold still…") }
            val start = System.nanoTime()
            delay(1_500)
            val samples = synchronized(bufferLock) { buffer.filter { it.first >= start }.map { it.second } }
            val msg = when {
                samples.size < 5 -> "Not enough sensor data — is the screen on and the sensor available?"
                else -> {
                    val (avg, spread) = MountingCalibration.average(samples)
                    if (spread > 0.6) "The phone moved during the measurement (%.1f°). Hold the telescope still and try again.".format(spread)
                    else onStill(avg, state.value)
                }
            }
            controls.update { it.copy(recording = false, message = msg ?: it.message) }
        }
    }

    val sourceLabel: String get() = sensor.capabilities.source.label
    val unsupported: Boolean get() = sensor.capabilities.source == OrientationSource.NONE
}

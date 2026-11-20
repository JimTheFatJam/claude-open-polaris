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
import io.github.jimthefatjam.polaris.core.telescope.DeviceAxis
import io.github.jimthefatjam.polaris.core.telescope.DirectionSmoother
import io.github.jimthefatjam.polaris.core.telescope.HorizontalDirection
import io.github.jimthefatjam.polaris.core.telescope.PointingMath
import io.github.jimthefatjam.polaris.core.telescope.PushToGuide
import io.github.jimthefatjam.polaris.data.ObservingRepository
import io.github.jimthefatjam.polaris.data.OrientationReading
import io.github.jimthefatjam.polaris.data.OrientationSensor
import io.github.jimthefatjam.polaris.data.OrientationSource
import io.github.jimthefatjam.polaris.data.SensorCapabilities
import io.github.jimthefatjam.polaris.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class TrackedObject(val obj: CelestialObject, val direction: HorizontalDirection)

data class TelescopeUi(
    val capabilities: SensorCapabilities,
    val hasReading: Boolean = false,
    val axis: DeviceAxis = DeviceAxis.TOP_EDGE,
    /** Raw pointing relative to magnetic north. */
    val magnetic: HorizontalDirection? = null,
    /** After magnetic-declination correction (true north). */
    val trueNorth: HorizontalDirection? = null,
    /** After applying the user's alignment offset — the best estimate of where the tube points. */
    val estimated: HorizontalDirection? = null,
    val declinationDeg: Double? = null,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val magneticFieldUt: Double? = null,
    val alignment: AlignmentOffset = AlignmentOffset(),
    val location: GeoLocation? = null,
    val candidates: List<TrackedObject> = emptyList(),
    val reference: TrackedObject? = null,
    val target: TrackedObject? = null,
    val guide: PushToGuide? = null,
) {
    /** Earth's field is roughly 25–65 µT; outside that range something nearby is distorting it. */
    val magneticInterference: Boolean get() = magneticFieldUt?.let { it < 20 || it > 70 } ?: false
}

private data class Controls(val alignment: AlignmentOffset = AlignmentOffset(), val referenceId: String? = null, val targetId: String? = null)

class TelescopeViewModel(
    private val sensor: OrientationSensor,
    private val settings: SettingsRepository,
    private val observing: ObservingRepository,
) : ViewModel() {
    private val sky = SkyCalculator()
    private val smoother = DirectionSmoother(alpha = 0.12)
    private val controls = MutableStateFlow(Controls())
    private var lastEstimated: HorizontalDirection? = null

    private val ticker = flow { while (true) { emit(Instant.now()); delay(2_000) } }

    /** Above-horizon alignment candidates and target positions, refreshed every 2 s. */
    private val skyPositions = combine(observing.state, ticker) { o, now ->
        val loc = o.location
        if (loc == null) loc to emptyList()
        else loc to Catalog.all.filter { it.type != ObjectType.SUN }.map { TrackedObject(it, dir(it, loc, now)) }
            .filter { it.direction.altitudeDeg > 0 }
    }

    val state: StateFlow<TelescopeUi> = combine(
        sensor.readings().map<OrientationReading, OrientationReading?> { it }.onStart { emit(null) },
        settings.settings.map { it.deviceAxis },
        controls,
        skyPositions,
    ) { reading, axis, c, (loc, positions) ->
        val declination = loc?.let {
            GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.elevationMeters.toFloat(), System.currentTimeMillis()).declination.toDouble()
        }
        val magnetic = reading?.let { smoother.update(PointingMath.pointing(it.matrix, axis)) }
        val trueDir = magnetic?.let { HorizontalDirection(PointingMath.magneticToTrue(it.azimuthDeg, declination ?: 0.0), it.altitudeDeg) }
        val estimated = trueDir?.let { c.alignment.apply(it) }
        lastEstimated = estimated
        val target = positions.firstOrNull { it.obj.id == c.targetId }
        val reference = positions.firstOrNull { it.obj.id == c.referenceId }
        val alignable = Catalog.alignmentCandidates.map { it.id }.toSet()
        TelescopeUi(
            capabilities = sensor.capabilities,
            hasReading = reading != null,
            axis = axis,
            magnetic = magnetic,
            trueNorth = trueDir,
            estimated = estimated,
            declinationDeg = declination,
            accuracy = reading?.accuracy ?: SensorManager.SENSOR_STATUS_UNRELIABLE,
            magneticFieldUt = reading?.magneticFieldUt,
            alignment = c.alignment,
            location = loc,
            candidates = positions.sortedByDescending { if (it.obj.id in alignable) 1 else 0 },
            reference = reference,
            target = target,
            guide = if (estimated != null && target != null) PointingMath.guide(estimated, target.direction) else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), TelescopeUi(sensor.capabilities))

    private fun dir(o: CelestialObject, loc: GeoLocation, now: Instant): HorizontalDirection {
        val p = sky.position(o, loc, now)
        return HorizontalDirection(p.azimuthDeg, p.altitudeDeg)
    }

    fun setAxis(a: DeviceAxis) {
        viewModelScope.launch { settings.setDeviceAxis(a) }
        smoother.reset()
        controls.update { it.copy(alignment = AlignmentOffset()) } // mounting changed: alignment no longer valid
    }

    fun chooseReference(id: String?) = controls.update { it.copy(referenceId = id) }
    fun chooseTarget(id: String?) = controls.update { it.copy(targetId = id) }

    /** One-star alignment on the chosen reference, using the current (declination-corrected) reading. */
    fun alignNow() {
        val s = state.value
        val measured = s.trueNorth ?: return
        val ref = s.reference ?: return
        controls.update { it.copy(alignment = AlignmentOffset.from(measured, ref.direction, ref.obj.name)) }
    }

    fun resetAlignment() = controls.update { it.copy(alignment = AlignmentOffset()) }

    val sourceLabel: String get() = sensor.capabilities.source.label
    val unsupported: Boolean get() = sensor.capabilities.source == OrientationSource.NONE
}

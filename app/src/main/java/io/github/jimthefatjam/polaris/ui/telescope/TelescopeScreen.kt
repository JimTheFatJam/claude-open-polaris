package io.github.jimthefatjam.polaris.ui.telescope

import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jimthefatjam.polaris.core.telescope.DeviceAxis
import io.github.jimthefatjam.polaris.core.telescope.HeadingSource
import io.github.jimthefatjam.polaris.core.telescope.MountingCalibration
import io.github.jimthefatjam.polaris.core.telescope.PushToGuide
import io.github.jimthefatjam.polaris.data.PointingMode
import io.github.jimthefatjam.polaris.di.AppContainer
import io.github.jimthefatjam.polaris.ui.components.Banner
import io.github.jimthefatjam.polaris.ui.components.BannerKind
import io.github.jimthefatjam.polaris.ui.components.Formatter
import io.github.jimthefatjam.polaris.ui.components.KeyValue
import io.github.jimthefatjam.polaris.ui.components.PolarisCard
import io.github.jimthefatjam.polaris.ui.components.factoryOf
import io.github.jimthefatjam.polaris.ui.theme.MonoNumbers
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class Picker { NONE, REFERENCE, TARGET, CALIBRATION }

@Composable
fun TelescopeScreen(container: AppContainer) {
    val vm: TelescopeViewModel = viewModel(factory = factoryOf { TelescopeViewModel(container.orientation, container.settings, container.observing) })
    val s by vm.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(Picker.NONE) }

    // Keep the screen awake while pointing a telescope.
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    val bright = s.candidates.filter { (it.obj.magnitude ?: 9.0) < 2.2 || it.obj.id == "moon" }
    when (picker) {
        Picker.REFERENCE -> ObjectPicker(if (s.pointingMode == PointingMode.PROFILE) "Sync on" else "Alignment reference", bright,
            { vm.chooseReference(it); picker = Picker.NONE }, { picker = Picker.NONE })
        Picker.CALIBRATION -> ObjectPicker("Calibration object", bright, { vm.chooseCalibrationTarget(it); picker = Picker.NONE }, { picker = Picker.NONE })
        Picker.TARGET -> ObjectPicker("Push-to target", s.candidates, { vm.chooseTarget(it); picker = Picker.NONE }, { picker = Picker.NONE })
        Picker.NONE -> {}
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Telescope", style = MaterialTheme.typography.headlineSmall)
            Text("Experimental push-to assistant", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
        }
        item {
            Banner("Phone sensors are accurate to a few degrees at best. Use this to get close, then use a finder or low-power eyepiece.",
                BannerKind.WARNING)
        }
        if (vm.unsupported) {
            item {
                PolarisCard(Modifier.fillMaxWidth()) {
                    Text("Sensors not available", style = MaterialTheme.typography.titleMedium)
                    Text("This device does not report a rotation-vector sensor or an accelerometer + magnetometer pair, so orientation can't be estimated. " +
                        "The rest of Polaris works normally.", color = PolarisColors.Muted)
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Accelerometer", yesNo(s.capabilities.accelerometer))
                    KeyValue("Magnetometer", yesNo(s.capabilities.magnetometer))
                    KeyValue("Gyroscope", yesNo(s.capabilities.gyroscope))
                }
            }
            return@LazyColumn
        }

        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("Pointing model", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PointingMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.pointingMode == m, { vm.setPointingMode(m) }, SegmentedButtonDefaults.itemShape(i, PointingMode.entries.size)) { Text(m.label) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (s.pointingMode == PointingMode.SIMPLE)
                        "Assumes the tube points along the phone's top edge or rear camera, plus a one-star offset each session."
                    else "Uses a saved phone-to-tube mounting calibration: the phone only needs to lie parallel to the tube, at any angle. Calibrate once, reuse every session.",
                    style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted,
                )
            }
        }

        s.calibration.message?.let { item { Banner(it, if (it.contains("moved") || it.contains("off by") || it.contains("must")) BannerKind.WARNING else BannerKind.INFO) } }

        item { LiveCard(s, vm.sourceLabel) }

        if (s.pointingMode == PointingMode.SIMPLE) {
            simpleModeItems(s, vm, onPickReference = { picker = Picker.REFERENCE })
        } else {
            profileModeItems(s, vm, onPickReference = { picker = Picker.REFERENCE }, onPickCalibration = { picker = Picker.CALIBRATION })
        }

        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("Push to a target", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { picker = Picker.TARGET }, enabled = s.location != null) { Text(s.target?.obj?.name ?: "Choose target") }
                val g = s.guide
                if (s.target != null && !s.azimuthValid) {
                    Text("Sync on a star first: gyro-only heading has no absolute direction.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
                }
                if (g != null) {
                    Spacer(Modifier.height(8.dp))
                    PushToView(g, Modifier.fillMaxWidth().height(220.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(guideText(g), style = MaterialTheme.typography.titleMedium,
                        color = if (g.separationDeg < 2) PolarisColors.Excellent else PolarisColors.Starlight)
                    KeyValue("Separation", "%.1f°".format(g.separationDeg))
                    val calibrated = if (s.pointingMode == PointingMode.SIMPLE) s.alignment.isAligned else s.activeProfile != null
                    if (!calibrated) Text("Not calibrated: expect errors of 5–20° from compass bias and mounting.",
                        style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
                }
            }
        }
        item { LimitationsCard() }
    }
}

// ---------------------------------------------------------------------- Simple mode (unchanged behaviour)

private fun androidx.compose.foundation.lazy.LazyListScope.simpleModeItems(s: TelescopeUi, vm: TelescopeViewModel, onPickReference: () -> Unit) {
    item {
        PolarisCard(Modifier.fillMaxWidth()) {
            Text("1 · How is the phone mounted?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DeviceAxis.entries.forEachIndexed { i, a ->
                    SegmentedButton(s.axis == a, { vm.setAxis(a) }, SegmentedButtonDefaults.itemShape(i, DeviceAxis.entries.size)) {
                        Text(if (a == DeviceAxis.TOP_EDGE) "Top edge" else "Rear camera")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (s.axis == DeviceAxis.TOP_EDGE) "Strap the phone flat along the tube with its top edge pointing where the telescope points."
                else "Mount the phone so its rear camera looks along the tube (screen facing you).",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted,
            )
            Text("If the phone is at an angle to the tube, use the Mounting profile model instead.",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
        }
    }
    item { CompassCard(s, "2 · Calibrate the compass") }
    item {
        PolarisCard(Modifier.fillMaxWidth()) {
            Text("3 · Align on a known object", style = MaterialTheme.typography.titleMedium)
            Text("Pick a bright star, planet or the Moon, centre it in the eyepiece, then tap Align. This one-star alignment corrects compass bias and mounting offset near that part of the sky.",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
            Spacer(Modifier.height(8.dp))
            if (s.location == null) Text("Set a location first (Tonight screen).", color = PolarisColors.Fair)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickReference, enabled = s.location != null) { Text(s.reference?.obj?.name ?: "Choose reference") }
                Button(onClick = vm::alignNow, enabled = s.reference != null && s.hasReading) { Text("Align") }
            }
            s.reference?.let { KeyValue("${it.obj.name} is at", "Az %.1f° · Alt %.1f°".format(it.direction.azimuthDeg, it.direction.altitudeDeg)) }
            if (s.alignment.isAligned) {
                KeyValue("Aligned on", s.alignment.referenceName!!, PolarisColors.Excellent)
                KeyValue("Offset applied", "Az %+.1f° · Alt %+.1f°".format(s.alignment.azimuthDeg, s.alignment.altitudeDeg))
                TextButton(onClick = vm::resetAlignment) { Text("Reset alignment") }
            } else {
                KeyValue("Alignment", "Not aligned", PolarisColors.Fair)
            }
        }
    }
}

// ---------------------------------------------------------------------- Profile mode

private fun androidx.compose.foundation.lazy.LazyListScope.profileModeItems(
    s: TelescopeUi, vm: TelescopeViewModel, onPickReference: () -> Unit, onPickCalibration: () -> Unit,
) {
    item { ProfilesCard(s, vm) }
    item { HeadingSourceCard(s, vm) }
    if (s.activeProfile != null) item { SyncCard(s, vm, onPickReference) }
    item { CalibrationCard(s, vm, onPickCalibration) }
}

@Composable
private fun ProfilesCard(s: TelescopeUi, vm: TelescopeViewModel) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Mounting profiles", style = MaterialTheme.typography.titleMedium)
        if (s.profiles.isEmpty()) {
            Text("No saved mounting yet. Calibrate below: you only need to do this once per way of strapping the phone to the telescope.",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
            return@PolarisCard
        }
        val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        s.profiles.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { vm.selectProfile(p.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p.id == s.activeProfile?.id, onClick = { vm.selectProfile(p.id) })
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    Text("Angle %.1f° from top edge · tilt %+.1f° · %d sightings · RMS %.1f° · %s".format(
                        p.inPlaneAngleDeg, p.tiltDeg, p.sightings, p.rmsDeg, fmt.format(Date(p.createdAtEpochMs))),
                        style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                    if (p.lowConfidence) Text("Low confidence (single sighting)", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
                }
                IconButton(onClick = { vm.deleteProfile(p.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${p.name}", tint = PolarisColors.Faint) }
            }
        }
        if (s.activeProfile == null) Text("Select a profile to use it.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
    }
}

@Composable
private fun HeadingSourceCard(s: TelescopeUi, vm: TelescopeViewModel) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Heading source", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            HeadingSource.entries.forEachIndexed { i, h ->
                SegmentedButton(
                    selected = s.headingSource == h, onClick = { vm.setHeadingSource(h) },
                    shape = SegmentedButtonDefaults.itemShape(i, HeadingSource.entries.size),
                    enabled = h == HeadingSource.COMPASS || s.capabilities.gameRotationVector,
                ) { Text(h.label) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(s.headingSource.description, style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
        if (!s.capabilities.gameRotationVector) Text("Gyro-only heading isn't available on this device.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
        if (s.headingSource == HeadingSource.COMPASS) {
            Spacer(Modifier.height(4.dp))
            KeyValue("Compass accuracy", accuracyLabel(s.accuracy), accuracyColor(s.accuracy))
            KeyValue("Magnetic field", s.magneticFieldUt?.let { "%.0f µT".format(it) } ?: "—",
                if (s.magneticInterference) PolarisColors.Poor else PolarisColors.Starlight)
            if (s.magneticInterference) Text("Field strength is outside Earth's normal 25–65 µT — likely magnetic interference. Try Gyro only.",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Poor)
        }
    }
}

@Composable
private fun SyncCard(s: TelescopeUi, vm: TelescopeViewModel, onPick: () -> Unit) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Session sync" + if (s.headingSource == HeadingSource.GYRO) " (required)" else " (optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            if (s.headingSource == HeadingSource.GYRO) "Gyro heading starts in an arbitrary direction. Centre one object and tap Sync; re-sync if pointing drifts."
            else "Corrects today's compass error with one object. Your saved mounting is reused as is.",
            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, enabled = s.location != null) { Text(s.reference?.obj?.name ?: "Choose object") }
            Button(onClick = vm::syncNow, enabled = s.reference != null && s.hasReading && !s.calibration.recording) { Text(if (s.calibration.recording) "Hold still…" else "Sync") }
        }
        if (s.sessionHeadingDeg != null) {
            KeyValue("Heading correction", "%+.1f°".format(s.sessionHeadingDeg))
            KeyValue("Synced", s.minutesSinceSync?.let { if (it < 1) "just now" else "$it min ago" } ?: "—",
                if (s.syncStale) PolarisColors.Fair else PolarisColors.Starlight)
            s.syncAltitudeErrorDeg?.let {
                KeyValue("Altitude check", "%+.1f°".format(it), if (abs(it) > 2) PolarisColors.Poor else PolarisColors.Excellent)
            }
            if (s.syncStale) Text("Gyro heading drifts over time — re-sync for best accuracy.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
            TextButton(onClick = vm::clearSync) { Text("Clear sync") }
        } else {
            KeyValue("Status", "Not synced", PolarisColors.Fair)
        }
    }
}

@Composable
private fun CalibrationCard(s: TelescopeUi, vm: TelescopeViewModel, onPick: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val cal = s.calibration
    PolarisCard(Modifier.fillMaxWidth()) {
        Text(if (s.profiles.isEmpty()) "Calibrate mounting" else "New mounting calibration", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. Strap the phone flat on (or parallel to) the tube, at any angle.\n" +
                "2. Choose a bright object, centre it in the eyepiece, hold still and tap Record.\n" +
                "3. Repeat for 2–5 objects, at clearly different altitudes (≥ 20° apart) and directions.\n" +
                "4. Compute, check the residuals, then save the profile.",
            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted,
        )
        Spacer(Modifier.height(8.dp))
        if (s.location == null) Text("Set a location first (Tonight screen).", color = PolarisColors.Fair)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, enabled = s.location != null) { Text(s.calibrationTarget?.obj?.name ?: "Choose object") }
            Button(onClick = vm::recordSighting, enabled = s.calibrationTarget != null && s.hasReading && !cal.recording) {
                Text(if (cal.recording) "Hold still…" else "Record")
            }
        }
        s.calibrationTarget?.let { KeyValue("${it.obj.name} is at", "Az %.1f° · Alt %.1f°".format(it.direction.azimuthDeg, it.direction.altitudeDeg)) }
        if (cal.sightings.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Sightings", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Faint)
            cal.sightings.forEachIndexed { i, sg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${sg.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("alt %.0f°".format(sg.target.altitudeDeg), style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                    sg.residualDeg?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("±%.1f°".format(it), style = MaterialTheme.typography.bodySmall,
                            color = if (it > 2) PolarisColors.Poor else PolarisColors.Excellent)
                    }
                    IconButton(onClick = { vm.removeSighting(i) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove sighting ${i + 1}", tint = PolarisColors.Faint) }
                }
            }
            val altSpread = cal.sightings.maxOf { it.target.altitudeDeg } - cal.sightings.minOf { it.target.altitudeDeg }
            if (cal.sightings.size >= 2 && altSpread < 20) Text("Tip: add an object at a different altitude (current spread %.0f°).".format(altSpread),
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::computeCalibration) { Text("Compute") }
                TextButton(onClick = vm::clearCalibration) { Text("Clear") }
            }
        }
        val r = cal.result
        if (r != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = PolarisColors.Outline)
            Spacer(Modifier.height(8.dp))
            Text("Result", style = MaterialTheme.typography.titleSmall)
            KeyValue("Tube direction vs top edge", "%.1f° clockwise".format(r.inPlaneAngleDeg))
            KeyValue("Out-of-plane tilt", "%+.1f°".format(r.tiltDeg), if (abs(r.tiltDeg) > MountingCalibration.TILT_WARNING_DEG) PolarisColors.Fair else PolarisColors.Starlight)
            KeyValue("Heading correction (this session)", if (r.headingSolved) "%+.1f°".format(r.headingCorrectionDeg) else "not separable")
            KeyValue("Residual RMS / worst", "%.1f° / %.1f°".format(r.rmsDeg, r.maxDeg), if (r.rmsDeg > 2) PolarisColors.Poor else PolarisColors.Excellent)
            r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair) }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Profile name") },
                placeholder = { Text("e.g. Refractor, phone on dew shield") })
            Spacer(Modifier.height(6.dp))
            Button(onClick = { vm.saveProfile(name); name = "" }, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
        }
    }
}

// ---------------------------------------------------------------------- shared pieces

@Composable
private fun CompassCard(s: TelescopeUi, title: String) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("Move the phone in a slow figure-8 a few times, away from metal, magnets, cars and power lines. " +
            "Accuracy should read “High”.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
        Spacer(Modifier.height(4.dp))
        KeyValue("Compass accuracy", accuracyLabel(s.accuracy), accuracyColor(s.accuracy))
        KeyValue("Magnetic field", s.magneticFieldUt?.let { "%.0f µT".format(it) } ?: "—",
            if (s.magneticInterference) PolarisColors.Poor else PolarisColors.Starlight)
        if (s.magneticInterference) Text("Field strength is outside Earth's normal 25–65 µT — likely magnetic interference (metal mount, phone case magnet).",
            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Poor)
    }
}

@Composable
private fun LimitationsCard() {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Limitations", style = MaterialTheme.typography.titleMedium)
        listOf(
            "Altitude comes from the accelerometer and is the most reliable value (typically ±0.5–1.5° after calibration).",
            "Compass heading is disturbed by steel mounts, tripods, magnets in cases and nearby electronics: 2–15° errors are common. A session sync removes most of it near the sync object.",
            "Gyro-only heading avoids magnetic interference but drifts, typically a few degrees per 10–30 minutes. Re-sync regularly.",
            "A mounting profile is valid only while the phone stays in the same position on the tube. If the Sync altitude check reports more than 2°, re-calibrate.",
            "Polaris corrects magnetic declination with the World Magnetic Model built into Android.",
            "This is not plate solving and not telescope-grade pointing. Treat the numbers as a guide to the right patch of sky.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

@Composable
private fun LiveCard(s: TelescopeUi, source: String) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Estimated telescope pointing", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted)
        val e = s.estimated
        if (e == null) {
            Text(if (s.pointingMode == PointingMode.PROFILE && s.activeProfile == null) "No active mounting profile — calibrate or select one below."
                else "Waiting for sensor data…", color = PolarisColors.Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Compass(e.azimuthDeg, e.altitudeDeg, s.azimuthValid, Modifier.size(120.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(if (s.azimuthValid) "Az %5.1f°".format(e.azimuthDeg) else "Az  —", style = MaterialTheme.typography.headlineSmall.merge(MonoNumbers))
                    Text("Alt %+5.1f°".format(e.altitudeDeg), style = MaterialTheme.typography.headlineSmall.merge(MonoNumbers))
                    val status = when {
                        !s.azimuthValid -> "sync needed for azimuth"
                        s.pointingMode == PointingMode.PROFILE -> (s.activeProfile?.name ?: "") + if (s.sessionHeadingDeg != null) " · synced" else " · compass only"
                        s.alignment.isAligned -> "aligned"
                        else -> "unaligned"
                    }
                    val good = s.azimuthValid && (s.sessionHeadingDeg != null || s.alignment.isAligned)
                    Text((if (s.azimuthValid) Formatter.compassPoint(e.azimuthDeg) + " · " else "") + status,
                        color = if (good) PolarisColors.Excellent else PolarisColors.Fair, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        s.magnetic?.let { KeyValue("Magnetic azimuth (raw)", "%.1f°".format(it.azimuthDeg)) }
        if (s.headingSource == HeadingSource.COMPASS) KeyValue("Magnetic declination", s.declinationDeg?.let { "%+.1f°".format(it) } ?: "needs location")
        Text("Sensor: " + if (s.headingSource == HeadingSource.GYRO) "Game rotation vector (gyro + accelerometer, no magnetometer)" else source,
            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
    }
}

@Composable
private fun Compass(az: Double, alt: Double, azValid: Boolean, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = if (azValid) "Compass showing azimuth ${az.toInt()} degrees" else "Compass, azimuth not available" }) {
        val r = size.minDimension / 2 - 4.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(PolarisColors.Outline, r, c, style = Stroke(1.5.dp.toPx()))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val inner = if (i % 3 == 0) r * 0.82f else r * 0.9f
            drawLine(PolarisColors.Faint, c + Offset((sin(a) * inner).toFloat(), (-cos(a) * inner).toFloat()),
                c + Offset((sin(a) * r).toFloat(), (-cos(a) * r).toFloat()), 1.dp.toPx())
        }
        if (azValid) {
            val a = Math.toRadians(az)
            val tip = c + Offset((sin(a) * r * 0.78f).toFloat(), (-cos(a) * r * 0.78f).toFloat())
            drawLine(PolarisColors.AccentWarm, c, tip, 3.dp.toPx())
        }
        drawCircle(PolarisColors.AccentWarm, 4.dp.toPx(), c)
        val altFrac = ((alt + 90) / 180).toFloat().coerceIn(0f, 1f)
        drawCircle(PolarisColors.Accent.copy(alpha = 0.25f), r * 0.35f * altFrac + 2f, c)
    }
}

@Composable
private fun PushToView(g: PushToGuide, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = guideText(g) }) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 - 8.dp.toPx()
        listOf(1f / 3f, 1f).forEach { f -> drawCircle(PolarisColors.Outline, r * f, c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))) }
        drawLine(PolarisColors.Faint, c - Offset(12.dp.toPx(), 0f), c + Offset(12.dp.toPx(), 0f), 1.dp.toPx())
        drawLine(PolarisColors.Faint, c - Offset(0f, 12.dp.toPx()), c + Offset(0f, 12.dp.toPx()), 1.dp.toPx())
        val scale = r / 30f
        var dx = (g.deltaAzimuthDeg * scale).toFloat()
        var dy = (-g.deltaAltitudeDeg * scale).toFloat()
        val len = kotlin.math.hypot(dx, dy)
        if (len > r) { dx = dx / len * r; dy = dy / len * r }
        val color = if (g.separationDeg < 2) PolarisColors.Excellent else PolarisColors.AccentWarm
        drawCircle(color, 8.dp.toPx(), c + Offset(dx, dy))
    }
}

private fun guideText(g: PushToGuide): String {
    if (g.separationDeg < 2) return "On target (within ${"%.1f".format(g.separationDeg)}°)"
    val az = if (abs(g.deltaAzimuthDeg) < 1) null else
        (if (g.deltaAzimuthDeg > 0) "Turn right " else "Turn left ") + "%.0f°".format(abs(g.deltaAzimuthDeg))
    val alt = if (abs(g.deltaAltitudeDeg) < 1) null else
        (if (g.deltaAltitudeDeg > 0) "raise " else "lower ") + "%.0f°".format(abs(g.deltaAltitudeDeg))
    return listOfNotNull(az, alt).joinToString(", ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun ObjectPicker(title: String, items: List<TrackedObject>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = PolarisColors.NightElevated,
        title = { Text(title) },
        text = {
            if (items.isEmpty()) Text("No suitable objects above the horizon right now.", color = PolarisColors.Muted)
            else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(items, key = { it.obj.id }) { t ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(t.obj.id) }.padding(vertical = 10.dp)) {
                        Text(t.obj.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${t.obj.type.label} · Alt %.0f° · Az %.0f°".format(t.direction.altitudeDeg, t.direction.azimuthDeg) +
                            (t.obj.magnitude?.let { " · mag %.1f".format(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                    }
                    HorizontalDivider(color = PolarisColors.Outline)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun yesNo(b: Boolean) = if (b) "Present" else "Missing"

private fun accuracyLabel(a: Int) = when (a) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low — calibrate"
    else -> "Unreliable — calibrate"
}

private fun accuracyColor(a: Int) = when (a) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> PolarisColors.Excellent
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> PolarisColors.Fair
    else -> PolarisColors.Poor
}

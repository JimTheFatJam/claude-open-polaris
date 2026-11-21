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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jimthefatjam.polaris.core.telescope.HeadingSource
import io.github.jimthefatjam.polaris.core.telescope.PlaneGuide
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class Picker { NONE, TARGET, SYNC }

@Composable
fun TelescopeScreen(container: AppContainer) {
    val vm: TelescopeViewModel = viewModel(factory = factoryOf { TelescopeViewModel(container.orientation, container.settings, container.observing) })
    val s by vm.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(Picker.NONE) }

    // Keep the screen awake while navigating.
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    when (picker) {
        Picker.TARGET -> ObjectPicker("Navigate to", s.candidates, { vm.chooseTarget(it); picker = Picker.NONE }, { picker = Picker.NONE })
        Picker.SYNC -> ObjectPicker("Sync on", s.candidates.filter { (it.obj.magnitude ?: 9.0) < 2.2 || it.obj.id == "moon" || it.obj.type == io.github.jimthefatjam.polaris.core.model.ObjectType.PLANET },
            { vm.chooseSyncObject(it); picker = Picker.NONE }, { picker = Picker.NONE })
        Picker.NONE -> {}
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Telescope", style = MaterialTheme.typography.headlineSmall)
            Text("Experimental sky navigation with the phone's sensors", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
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
                Text("Navigation mode", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PointingMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.mode == m, { vm.setMode(m) }, SegmentedButtonDefaults.itemShape(i, PointingMode.entries.size)) { Text(m.label) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (s.mode) {
                        PointingMode.CAMERA -> "Point the back of the phone straight at the object, like taking its photo."
                        PointingMode.PLANE -> "Tilt the phone until the object lies on its plane's most vertical line (the steepest line across the screen). " +
                            "Spinning the phone within its plane (top edge left or right) doesn't matter — only the plane's tilt does."
                    },
                    style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted,
                )
            }
        }

        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Target", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted)
                        Text(s.target?.obj?.name ?: "None selected", style = MaterialTheme.typography.titleLarge)
                        s.target?.let {
                            Text("Az %.1f° (%s) · Alt %.1f°".format(it.direction.azimuthDeg, Formatter.compassPoint(it.direction.azimuthDeg), it.direction.altitudeDeg),
                                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                        }
                        s.targetMotion?.let { m ->
                            Text("Live: moving %.2f°/min (az %+.2f, alt %+.2f) · updated every second".format(m.totalDegPerMin, m.azimuthDegPerMin, m.altitudeDegPerMin),
                                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Accent)
                        }
                    }
                    OutlinedButton(onClick = { picker = Picker.TARGET }, enabled = s.location != null) { Text(if (s.target == null) "Choose" else "Change") }
                }
                if (s.location == null) Text("Set a location first (Tonight screen).", color = PolarisColors.Fair, style = MaterialTheme.typography.bodySmall)
            }
        }

        s.message?.let { item { Banner(it, if (it.contains("moved") || it.contains("off by")) BannerKind.WARNING else BannerKind.INFO) } }

        item {
            when (s.mode) {
                PointingMode.PLANE -> PlaneCard(s)
                PointingMode.CAMERA -> CameraCard(s)
            }
        }
        if (s.mode == PointingMode.PLANE) item { TelescopeSyncCard(s, vm, onPickSync = { picker = Picker.SYNC }) }
        item { HeadingCard(s, vm, onPickSync = { picker = Picker.SYNC }) }
        item { LimitationsCard(s.mode) }
    }
}

// ---------------------------------------------------------------------- Plane mode

@Composable
private fun PlaneCard(s: TelescopeUi) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (s.telescopeSynced && s.hasReading) SyncedScopeCard(s)
    PolarisCard(Modifier.fillMaxWidth()) {
        Text(if (s.telescopeSynced) "Phone plane (reference)" else "Phone plane", style = MaterialTheme.typography.titleMedium)
        if (s.telescopeSynced) Text("Guidance above follows the synced telescope line of sight; the plane view below is for reference.",
            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
        val g = s.planeGuide
        when {
            !s.hasReading -> Text("Waiting for sensor data…", color = PolarisColors.Muted)
            s.target == null -> Text("Choose a target to navigate to.", color = PolarisColors.Muted)
            !s.headingValid -> Text("Gyro-only heading has no absolute direction yet — do a telescope sync below first.", color = PolarisColors.Fair)
            g != null -> {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaneDiagram(g, Modifier.size(width = 120.dp, height = 190.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pinpoint error", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted)
                        Text(g.separationDeg?.let { "%.1f°".format(it) } ?: "—", style = MaterialTheme.typography.displayMedium.merge(MonoNumbers),
                            color = g.separationDeg?.let { offsetColor(it) } ?: PolarisColors.Faint)
                        Text(g.instruction(), style = MaterialTheme.typography.titleMedium,
                            color = if (g.aligned) PolarisColors.Excellent else PolarisColors.Starlight)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Off plane %+.1f°".format(g.offsetDeg), style = MaterialTheme.typography.labelMedium, color = offsetColor(g.offsetDeg))
                AlignmentMeter(g.offsetDeg, Modifier.fillMaxWidth().height(28.dp))
                Text(g.planeInstruction(), style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                Spacer(Modifier.height(8.dp))
                g.verticalLine?.let {
                    KeyValue("Plane's vertical line points", "Az %.0f° (%s) · Alt %.0f°".format(it.azimuthDeg, Formatter.compassPoint(it.azimuthDeg), it.altitudeDeg))
                }
                g.verticalLineInPlaneDeg?.let {
                    Text("On the phone, the vertical line runs toward the ${PlaneGuide.edgeName(it)} edge (dashed); the arrow shows the target.",
                        style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
                }
            }
        }
        s.planeNormal?.let {
            Spacer(Modifier.height(6.dp))
            KeyValue("Screen faces", if (s.headingValid) "Az %.0f° (%s) · Alt %+.0f°".format(it.azimuthDeg, Formatter.compassPoint(it.azimuthDeg), it.altitudeDeg)
                else "Alt %+.0f° (azimuth needs a sync)".format(it.altitudeDeg))
        }
    }
}

/**
 * Phone outline (screen view): dashed line = the plane's most vertical line, arrow = the target's
 * direction within the plane, highlighted edge = which edge to tilt to bring the target into the plane.
 * At pinpoint alignment the arrow lies on the dashed line.
 */
@Composable
private fun PlaneDiagram(g: PlaneGuide, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = g.instruction() }) {
        val w = size.width * 0.72f
        val h = size.height * 0.86f
        val tl = Offset((size.width - w) / 2, (size.height - h) / 2)
        val c = Offset(size.width / 2, size.height / 2)
        val color = offsetColor(g.offsetDeg)
        drawRoundRect(PolarisColors.SurfaceHigh, tl, Size(w, h), CornerRadius(14.dp.toPx()))
        drawRoundRect(PolarisColors.Outline, tl, Size(w, h), CornerRadius(14.dp.toPx()), style = Stroke(2.dp.toPx()))
        // Edge (or corner) to tilt: the point where a ray from the centre at edgeDeg meets the outline.
        // The plane's most vertical line (dashed), from the centre toward the edge it runs to.
        g.verticalLineInPlaneDeg?.let { d ->
            val a = Math.toRadians(d)
            val len = min(w, h) * 0.46f
            drawLine(PolarisColors.Accent, c, c + Offset((sin(a) * len).toFloat(), (-cos(a) * len).toFloat()), 2.5.dp.toPx(),
                StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        if (!g.inPlane) {
            val e = Math.toRadians(g.edgeDeg)
            val dx = sin(e).toFloat(); val dy = -cos(e).toFloat()
            val t = min(if (dx != 0f) (w / 2) / abs(dx) else Float.MAX_VALUE, if (dy != 0f) (h / 2) / abs(dy) else Float.MAX_VALUE)
            drawCircle(color.copy(alpha = 0.35f), 16.dp.toPx(), c + Offset(dx * t, dy * t))
            drawCircle(color, 6.dp.toPx(), c + Offset(dx * t, dy * t))
        }
        // Arrow toward the target's direction within the plane.
        val a = Math.toRadians(g.inPlaneDirectionDeg)
        val len = min(w, h) * 0.42f
        val tip = c + Offset((sin(a) * len).toFloat(), (-cos(a) * len).toFloat())
        drawLine(PolarisColors.AccentWarm, c, tip, 3.dp.toPx(), StrokeCap.Round)
        drawCircle(PolarisColors.AccentWarm, 5.dp.toPx(), tip)
        drawCircle(PolarisColors.Faint, 3.dp.toPx(), c)
    }
}

/** Horizontal meter: centre = aligned; ±30° full scale. */
@Composable
private fun AlignmentMeter(offsetDeg: Double, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Plane offset %.1f degrees".format(offsetDeg) }) {
        val midY = size.height / 2
        drawLine(PolarisColors.Outline, Offset(0f, midY), Offset(size.width, midY), 4.dp.toPx(), StrokeCap.Round)
        val okHalf = size.width / 2 * (PlaneGuide.ALIGNED_DEG / 30.0).toFloat().coerceAtLeast(0.03f)
        drawLine(PolarisColors.Excellent.copy(alpha = 0.6f), Offset(size.width / 2 - okHalf, midY), Offset(size.width / 2 + okHalf, midY), 8.dp.toPx())
        drawLine(PolarisColors.Faint, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
        val x = (size.width / 2 + (offsetDeg / 30.0).coerceIn(-1.0, 1.0) * size.width / 2).toFloat()
        drawCircle(offsetColor(offsetDeg), 9.dp.toPx(), Offset(x, midY))
    }
}

private fun offsetColor(offset: Double) = when {
    abs(offset) < PlaneGuide.ALIGNED_DEG -> PolarisColors.Excellent
    abs(offset) < 5 -> PolarisColors.Good
    abs(offset) < 15 -> PolarisColors.Fair
    else -> PolarisColors.AccentWarm
}

// ---------------------------------------------------------------------- Camera mode

@Composable
private fun CameraCard(s: TelescopeUi) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Back of phone points at", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted)
        val e = s.camera
        if (e == null) {
            Text("Waiting for sensor data…", color = PolarisColors.Muted)
            return@PolarisCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Compass(e.azimuthDeg, e.altitudeDeg, s.headingValid, Modifier.size(110.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(if (s.headingValid) "Az %5.1f°".format(e.azimuthDeg) else "Az  —", style = MaterialTheme.typography.headlineSmall.merge(MonoNumbers))
                Text("Alt %+5.1f°".format(e.altitudeDeg), style = MaterialTheme.typography.headlineSmall.merge(MonoNumbers))
                if (s.headingValid) Text(Formatter.compassPoint(e.azimuthDeg), style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
            }
        }
        val g = s.cameraGuide
        if (s.target != null && !s.headingValid) Text("Sync on a star first: gyro-only heading has no absolute direction.", color = PolarisColors.Fair, style = MaterialTheme.typography.bodySmall)
        if (g != null) {
            Spacer(Modifier.height(8.dp))
            PushToView(g, Modifier.fillMaxWidth().height(200.dp))
            Spacer(Modifier.height(8.dp))
            Text(guideText(g), style = MaterialTheme.typography.titleMedium, color = if (g.separationDeg < 2) PolarisColors.Excellent else PolarisColors.Starlight)
            KeyValue("Separation", "%.1f°".format(g.separationDeg))
        }
    }
}

// ---------------------------------------------------------------------- Telescope sync

/** Pointing that follows the telescope's synced line of sight (plane mode). */
@Composable
private fun SyncedScopeCard(s: TelescopeUi) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Telescope line of sight (synced)", style = MaterialTheme.typography.titleMedium)
        val p = s.scopePointing
        if (p != null) Text("Pointing at Az %.1f° (%s) · Alt %+.1f°".format(p.azimuthDeg, Formatter.compassPoint(p.azimuthDeg), p.altitudeDeg),
            style = MaterialTheme.typography.bodyMedium, color = PolarisColors.Muted)
        val g = s.scopeGuide
        if (g == null) {
            Text("Choose a target to navigate to.", color = PolarisColors.Muted)
            return@PolarisCard
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pinpoint error", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted)
                Text("%.1f°".format(g.separationDeg), style = MaterialTheme.typography.displayMedium.merge(MonoNumbers), color = offsetColor(g.separationDeg))
                Text(scopeText(g), style = MaterialTheme.typography.titleMedium,
                    color = if (g.separationDeg < PlaneGuide.ALIGNED_DEG) PolarisColors.Excellent else PolarisColors.Starlight)
            }
        }
        Spacer(Modifier.height(8.dp))
        PushToView(g, Modifier.fillMaxWidth().height(180.dp))
        s.telescopeCalibration?.let { c ->
            if (c.residualsDeg.size == 1) Text("Calibrated on one object: most accurate near ${s.telescopeSyncs.firstOrNull()?.name}. Add a second sync elsewhere in the sky for whole-sky accuracy.",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
        }
    }
}

private fun scopeText(g: PushToGuide): String {
    if (g.separationDeg < PlaneGuide.ALIGNED_DEG) return "On target"
    val az = if (abs(g.deltaAzimuthDeg) < 0.5) null else (if (g.deltaAzimuthDeg > 0) "Turn right " else "Turn left ") + "%.1f°".format(abs(g.deltaAzimuthDeg))
    val alt = if (abs(g.deltaAltitudeDeg) < 0.5) null else (if (g.deltaAltitudeDeg > 0) "raise the telescope " else "lower the telescope ") + "%.1f°".format(abs(g.deltaAltitudeDeg))
    return listOfNotNull(az, alt).joinToString(", ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun TelescopeSyncCard(s: TelescopeUi, vm: TelescopeViewModel, onPickSync: () -> Unit) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Telescope sync" + if (s.headingSource == HeadingSource.GYRO && !s.telescopeSynced) " (required)" else "", style = MaterialTheme.typography.titleMedium)
        Text("Centre a bright object (the Moon is easiest) in the telescope, or in the phone camera looking through it. Hold still and tap Sync. " +
            "The app then follows the telescope's real line of sight instead of the phone's plane, which removes the mounting and compass error. " +
            "Then sync a second object at a different altitude (e.g. a planet or a bright star) to correct the whole sky.",
            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickSync, enabled = s.location != null) { Text(s.syncObject?.obj?.name ?: "Choose object") }
            Button(onClick = vm::telescopeSync, enabled = s.syncObject != null && s.hasReading && !s.recording) {
                Text(if (s.recording) "Hold still…" else if (s.telescopeSynced) "Add sync" else "Sync")
            }
        }
        s.syncObject?.let { KeyValue("${it.obj.name} is at", "Az %.1f° · Alt %.1f°".format(it.direction.azimuthDeg, it.direction.altitudeDeg)) }
        if (s.telescopeSyncs.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val timeFmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
            s.telescopeSyncs.forEachIndexed { i, sy ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${i + 1}. ${sy.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(timeFmt.format(java.util.Date.from(sy.time)), style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                    sy.residualDeg?.takeIf { s.telescopeSyncs.size > 1 }?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("±%.1f°".format(it), style = MaterialTheme.typography.bodySmall, color = if (it > 2) PolarisColors.Poor else PolarisColors.Excellent)
                    }
                }
            }
            s.telescopeCalibration?.let { c ->
                KeyValue("Heading correction", if (c.headingSolved) "%+.1f° (solved)".format(c.headingCorrectionDeg) else "included in the sync")
                if (c.residualsDeg.size > 1) KeyValue("Fit RMS", "%.1f°".format(c.rmsDeg), if (c.rmsDeg > 2) PolarisColors.Poor else PolarisColors.Excellent)
                c.warnings.filterNot { it.contains("one sighting") }.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair) }
            }
            if (s.headingSource == HeadingSource.GYRO) Text("Gyro mode: syncs last for this session only.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
            else Text("Saved — reused next time, as long as the phone stays in the same place on the telescope.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
            TextButton(onClick = vm::resetTelescopeSync) { Text("Reset telescope sync") }
        }
    }
}

// ---------------------------------------------------------------------- Heading & sync

@Composable
private fun HeadingCard(s: TelescopeUi, vm: TelescopeViewModel, onPickSync: () -> Unit) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Heading", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            HeadingSource.entries.forEachIndexed { i, h ->
                SegmentedButton(selected = s.headingSource == h, onClick = { vm.setHeadingSource(h) },
                    shape = SegmentedButtonDefaults.itemShape(i, HeadingSource.entries.size),
                    enabled = h == HeadingSource.COMPASS || s.capabilities.gameRotationVector) { Text(h.label) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(s.headingSource.description, style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
        if (!s.capabilities.gameRotationVector) Text("Gyro-only heading isn't available on this device.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
        if (s.headingSource == HeadingSource.COMPASS) {
            KeyValue("Compass accuracy", accuracyLabel(s.accuracy), accuracyColor(s.accuracy))
            KeyValue("Magnetic field", s.magneticFieldUt?.let { "%.0f µT".format(it) } ?: "—", if (s.magneticInterference) PolarisColors.Poor else PolarisColors.Starlight)
            KeyValue("Magnetic declination", s.declinationDeg?.let { "%+.1f° (corrected)".format(it) } ?: "needs location")
            if (s.magneticInterference) Text("Field strength is outside Earth's normal 25–65 µT — likely magnetic interference. Move away from metal or try Gyro only.",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Poor)
            if (s.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) Text("Move the phone in a slow figure-8 to calibrate the compass.",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
        }

        if (s.mode != PointingMode.CAMERA) return@PolarisCard
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = PolarisColors.Outline)
        Spacer(Modifier.height(10.dp))
        Text("Star sync" + if (s.headingSource == HeadingSource.GYRO) " (required)" else " (optional)", style = MaterialTheme.typography.titleSmall)
        Text("Aim the back of the phone at a bright object, hold still and tap Sync. This corrects the heading for this session.",
            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickSync, enabled = s.location != null) { Text(s.syncObject?.obj?.name ?: "Choose object") }
            Button(onClick = vm::syncNow, enabled = s.syncObject != null && s.hasReading && !s.recording) { Text(if (s.recording) "Hold still…" else "Sync") }
        }
        if (s.sessionHeadingDeg != null) {
            KeyValue("Heading correction", "%+.1f°".format(s.sessionHeadingDeg))
            KeyValue("Synced", s.minutesSinceSync?.let { if (it < 1) "just now" else "$it min ago" } ?: "—", if (s.syncStale) PolarisColors.Fair else PolarisColors.Starlight)
            if (s.syncStale) Text("Gyro heading drifts — re-sync for best accuracy.", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
            TextButton(onClick = vm::clearSync) { Text("Clear sync") }
        }
    }
}

@Composable
private fun LimitationsCard(mode: PointingMode) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Accuracy", style = MaterialTheme.typography.titleMedium)
        listOfNotNull(
            "Tilt (altitude) comes from gravity via the accelerometer and is the most reliable part, typically within 1°.",
            "Heading comes from the compass (disturbed by metal, magnets and electronics; errors of 2–15° are common) or from the gyro (drifts a few degrees per 10–30 minutes). A sync removes most of the error.",
            "Objects move across the sky (up to 0.25°/min). Positions are recomputed every second, and syncs use the object's position at the moment of capture.",
            if (mode == PointingMode.PLANE) "After a telescope sync, accuracy is best near the sync object(s); two syncs at different altitudes extend it across the sky. Re-sync if you move the phone on the telescope." else null,
            if (mode == PointingMode.PLANE) "Without a sync, plane mode points with the plane's most vertical line (altitude = 90° − the phone's tilt). That line is undefined when the phone lies level and very sensitive when it's nearly upright." else null,
            "Not telescope-grade pointing: use it to get close, then a finder or low-power eyepiece.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted, modifier = Modifier.padding(vertical = 2.dp)) }
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
            drawLine(PolarisColors.AccentWarm, c, c + Offset((sin(a) * r * 0.78f).toFloat(), (-cos(a) * r * 0.78f).toFloat()), 3.dp.toPx())
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
        drawCircle(if (g.separationDeg < 2) PolarisColors.Excellent else PolarisColors.AccentWarm, 8.dp.toPx(), c + Offset(dx, dy))
    }
}

private fun guideText(g: PushToGuide): String {
    if (g.separationDeg < 2) return "On target (within ${"%.1f".format(g.separationDeg)}°)"
    val az = if (abs(g.deltaAzimuthDeg) < 1) null else (if (g.deltaAzimuthDeg > 0) "Turn right " else "Turn left ") + "%.0f°".format(abs(g.deltaAzimuthDeg))
    val alt = if (abs(g.deltaAltitudeDeg) < 1) null else (if (g.deltaAltitudeDeg > 0) "raise " else "lower ") + "%.0f°".format(abs(g.deltaAltitudeDeg))
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

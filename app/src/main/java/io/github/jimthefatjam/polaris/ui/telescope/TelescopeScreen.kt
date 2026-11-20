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
import io.github.jimthefatjam.polaris.core.telescope.PushToGuide
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

@Composable
fun TelescopeScreen(container: AppContainer) {
    val vm: TelescopeViewModel = viewModel(factory = factoryOf { TelescopeViewModel(container.orientation, container.settings, container.observing) })
    val s by vm.state.collectAsStateWithLifecycle()
    var pickRef by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf(false) }

    // Keep the screen awake while pointing a telescope.
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    if (pickRef) ObjectPicker("Alignment reference", s.candidates.filter { (it.obj.magnitude ?: 9.0) < 2.2 || it.obj.id == "moon" },
        { vm.chooseReference(it); pickRef = false }, { pickRef = false })
    if (pickTarget) ObjectPicker("Push-to target", s.candidates, { vm.chooseTarget(it); pickTarget = false }, { pickTarget = false })

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

        item { LiveCard(s, vm.sourceLabel) }
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
                Text("The phone and tube are never perfectly parallel; alignment (step 3) measures and removes that offset.",
                    style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
            }
        }
        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("2 · Calibrate the compass", style = MaterialTheme.typography.titleMedium)
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
        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("3 · Align on a known object", style = MaterialTheme.typography.titleMedium)
                Text("Pick a bright star, planet or the Moon, centre it in the eyepiece, then tap Align. This one-star alignment corrects compass bias and mounting offset near that part of the sky.",
                    style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                Spacer(Modifier.height(8.dp))
                if (s.location == null) Text("Set a location first (Tonight screen).", color = PolarisColors.Fair)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickRef = true }, enabled = s.location != null) { Text(s.reference?.obj?.name ?: "Choose reference") }
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
        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("4 · Push to a target", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { pickTarget = true }, enabled = s.location != null) { Text(s.target?.obj?.name ?: "Choose target") }
                val g = s.guide
                if (g != null) {
                    Spacer(Modifier.height(8.dp))
                    PushToView(g, Modifier.fillMaxWidth().height(220.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(guideText(g), style = MaterialTheme.typography.titleMedium,
                        color = if (g.separationDeg < 2) PolarisColors.Excellent else PolarisColors.Starlight)
                    KeyValue("Separation", "%.1f°".format(g.separationDeg))
                    if (!s.alignment.isAligned) Text("Not aligned: expect errors of 5–20° from compass bias and mounting.",
                        style = MaterialTheme.typography.bodySmall, color = PolarisColors.Fair)
                }
            }
        }
        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("Limitations", style = MaterialTheme.typography.titleMedium)
                listOf(
                    "Magnetometers are easily disturbed by steel mounts, tripods, magnets in cases and nearby electronics. Errors of 5–15° are common.",
                    "The rotation-vector sensor fuses gyroscope, accelerometer and magnetometer; the gyro smooths motion, but heading still follows the magnetometer, so interference shows up as slow drift.",
                    "One-star alignment removes a constant offset. It cannot correct an unlevelled mount or a phone that flexes; accuracy degrades far from the alignment object.",
                    "Polaris corrects for magnetic declination with the World Magnetic Model built into Android.",
                    "This is not plate solving and not telescope-grade pointing. Treat the numbers as a guide to the right patch of sky.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}

@Composable
private fun LiveCard(s: TelescopeUi, source: String) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Estimated pointing", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted)
        val e = s.estimated
        if (e == null) {
            Text("Waiting for sensor data…", color = PolarisColors.Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Compass(e.azimuthDeg, e.altitudeDeg, Modifier.size(120.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Az %5.1f°".format(e.azimuthDeg), style = MaterialTheme.typography.headlineSmall.merge(MonoNumbers))
                    Text("Alt %+5.1f°".format(e.altitudeDeg), style = MaterialTheme.typography.headlineSmall.merge(MonoNumbers))
                    Text(Formatter.compassPoint(e.azimuthDeg) + if (s.alignment.isAligned) " · aligned" else " · unaligned",
                        color = if (s.alignment.isAligned) PolarisColors.Excellent else PolarisColors.Fair, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        s.magnetic?.let { KeyValue("Magnetic azimuth (raw)", "%.1f°".format(it.azimuthDeg)) }
        KeyValue("Magnetic declination", s.declinationDeg?.let { "%+.1f°".format(it) } ?: "needs location")
        Text("Sensor: $source", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
    }
}

@Composable
private fun Compass(az: Double, alt: Double, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Compass showing azimuth ${az.toInt()} degrees" }) {
        val r = size.minDimension / 2 - 4.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(PolarisColors.Outline, r, c, style = Stroke(1.5.dp.toPx()))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val inner = if (i % 3 == 0) r * 0.82f else r * 0.9f
            drawLine(PolarisColors.Faint, c + Offset((sin(a) * inner).toFloat(), (-cos(a) * inner).toFloat()),
                c + Offset((sin(a) * r).toFloat(), (-cos(a) * r).toFloat()), 1.dp.toPx())
        }
        val a = Math.toRadians(az)
        val tip = c + Offset((sin(a) * r * 0.78f).toFloat(), (-cos(a) * r * 0.78f).toFloat())
        drawLine(PolarisColors.AccentWarm, c, tip, 3.dp.toPx())
        drawCircle(PolarisColors.AccentWarm, 4.dp.toPx(), c)
        // Altitude indicator: inner ring fill proportional to altitude.
        val altFrac = ((alt + 90) / 180).toFloat().coerceIn(0f, 1f)
        drawCircle(PolarisColors.Accent.copy(alpha = 0.25f), r * 0.35f * altFrac + 2f, c)
    }
}

@Composable
private fun PushToView(g: PushToGuide, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = guideText(g) }) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 - 8.dp.toPx()
        // Scale: ring = 10°, outer = 30° (clamped).
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

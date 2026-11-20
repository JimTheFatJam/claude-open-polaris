package io.github.jimthefatjam.polaris.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jimthefatjam.polaris.core.model.ObjectPosition
import io.github.jimthefatjam.polaris.core.model.ObjectType
import io.github.jimthefatjam.polaris.data.UserSettings
import io.github.jimthefatjam.polaris.di.AppContainer
import io.github.jimthefatjam.polaris.ui.components.Banner
import io.github.jimthefatjam.polaris.ui.components.Formatter
import io.github.jimthefatjam.polaris.ui.components.PolarisCard
import io.github.jimthefatjam.polaris.ui.components.rememberFormatter
import io.github.jimthefatjam.polaris.ui.explore.ObjectDetailSheet
import io.github.jimthefatjam.polaris.ui.explore.ObjectListRow
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SkyFilter(val label: String, val types: Set<ObjectType>) {
    ALL("All", ObjectType.entries.toSet()),
    SOLAR("Moon & planets", setOf(ObjectType.MOON, ObjectType.PLANET, ObjectType.SUN)),
    STARS("Stars", setOf(ObjectType.STAR, ObjectType.DOUBLE_STAR)),
    DEEP("Deep sky", ObjectType.entries.filter { it.isDeepSky }.toSet()),
}

@Composable
fun SkyScreen(container: AppContainer) {
    val obs by container.observing.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(UserSettings())
    val fmt = rememberFormatter(obs.zone, settings.units)
    var filter by remember { mutableStateOf(SkyFilter.ALL) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ObjectPosition?>(null) }

    val sky = obs.sky
    val visible = sky?.aboveHorizon.orEmpty().filter { it.obj.type in filter.types }
    val selected = visible.firstOrNull { it.obj.id == selectedId }

    detail?.let { ObjectDetailSheet(it, fmt, onDismiss = { detail = null }) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Sky", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (sky == null) "Waiting for a location…"
                else "${visible.size} objects above the horizon · ${fmt.time(obs.computedAt)}",
                style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SkyFilter.entries) { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            }
        }
        if (sky == null) {
            item { Banner("Set a location on the Tonight screen to see your sky.") }
        } else {
            item {
                PolarisCard(Modifier.fillMaxWidth()) {
                    SkyMap(visible, selectedId, onSelect = { selectedId = it?.obj?.id }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(4.dp))
                    Text("Looking up: zenith at the centre, horizon at the edge, north at the top, east on the left. Pinch to zoom, drag to rotate.",
                        style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
                }
            }
            if (selected != null) item {
                PolarisCard(Modifier.fillMaxWidth(), onClick = { detail = selected }) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(selected.obj.name, style = MaterialTheme.typography.titleMedium)
                            Text("${selected.obj.type.label} · ${selected.visibility.label}", color = PolarisColors.Muted, style = MaterialTheme.typography.bodySmall)
                            Text("Alt ${selected.altitudeDeg.roundToInt()}° · Az ${selected.azimuthDeg.roundToInt()}° (${Formatter.compassPoint(selected.azimuthDeg)})",
                                style = MaterialTheme.typography.bodyMedium)
                            Text(fmt.riseSetLine(selected.riseSet, true), style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                        }
                        TextButton(onClick = { detail = selected }) { Text("Details") }
                    }
                }
            }
            item { Text("ABOVE THE HORIZON — BEST FIRST", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted) }
            val sorted = visible.sortedWith(compareByDescending<ObjectPosition> { it.observability }.thenByDescending { it.altitudeDeg })
            items(sorted, key = { it.obj.id }) { p -> ObjectListRow(p, fmt) { detail = p } }
        }
    }
}

/** Polar (azimuthal equidistant) projection of the visible hemisphere. */
@Composable
fun SkyMap(objects: List<ObjectPosition>, selectedId: String?, onSelect: (ObjectPosition?) -> Unit, modifier: Modifier = Modifier) {
    var rotation by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val textColor = PolarisColors.Muted.toArgb()

    fun project(alt: Double, az: Double, cx: Float, cy: Float, radius: Float): Offset {
        val r = ((90 - alt) / 90.0 * radius * zoom).toFloat()
        val a = Math.toRadians(az + rotation)
        // North up, east LEFT (we are looking up at the sky).
        return Offset(cx - (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat())
    }

    Canvas(
        modifier
            .semantics { contentDescription = "Sky map with ${objects.size} objects above the horizon" }
            .pointerInput(objects) {
                detectTapGestures { tap ->
                    val cx = size.width / 2f; val cy = size.height / 2f; val radius = size.width / 2f * 0.92f
                    val hit = objects.minByOrNull { p ->
                        val o = project(p.altitudeDeg, p.azimuthDeg, cx, cy, radius)
                        hypot(o.x - tap.x, o.y - tap.y)
                    }?.takeIf { p ->
                        val o = project(p.altitudeDeg, p.azimuthDeg, cx, cy, radius)
                        hypot(o.x - tap.x, o.y - tap.y) < 32.dp.toPx()
                    }
                    onSelect(hit)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, 3f)
                    // Horizontal drag rotates the map around the zenith.
                    rotation += pan.x * 0.3f
                }
            },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.width / 2f * 0.92f
        drawCircle(Color(0xFF0B1433), radius * zoom.coerceAtMost(1.08f), Offset(cx, cy))
        listOf(30.0, 60.0).forEach { alt ->
            drawCircle(PolarisColors.Outline.copy(alpha = 0.6f), ((90 - alt) / 90 * radius * zoom).toFloat(), Offset(cx, cy),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        }
        drawCircle(PolarisColors.Accent.copy(alpha = 0.6f), radius * zoom, Offset(cx, cy), style = Stroke(1.5.dp.toPx()))

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true; color = textColor; textSize = 12.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        }
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, az) ->
            val p = project(-6.0, az, cx, cy, radius)
            drawContext.canvas.nativeCanvas.drawText(label, p.x, p.y + 4.dp.toPx(), paint)
        }

        val labelPaint = android.graphics.Paint(paint).apply { textSize = 10.dp.toPx(); color = PolarisColors.Starlight.copy(alpha = 0.8f).toArgb() }
        objects.sortedByDescending { it.magnitude ?: 99.0 }.forEach { p ->
            val o = project(p.altitudeDeg, p.azimuthDeg, cx, cy, radius)
            if (hypot(o.x - cx, o.y - cy) > radius * 1.05f && zoom > 1f) return@forEach
            drawObject(p, o)
            val labelled = (p.magnitude ?: 9.0) < 1.0 || p.obj.type == ObjectType.MOON || p.obj.id == selectedId
            if (labelled) drawContext.canvas.nativeCanvas.drawText(p.obj.name, o.x, o.y - 8.dp.toPx(), labelPaint)
            if (p.obj.id == selectedId) drawCircle(PolarisColors.AccentWarm, 12.dp.toPx(), o, style = Stroke(1.5.dp.toPx()))
        }
    }
}

private fun DrawScope.drawObject(p: ObjectPosition, o: Offset) {
    when (p.obj.type) {
        ObjectType.SUN -> drawCircle(Color(0xFFFFC94D), 8.dp.toPx(), o)
        ObjectType.MOON -> drawCircle(PolarisColors.MoonGlow, 7.dp.toPx(), o)
        ObjectType.PLANET -> drawCircle(PolarisColors.AccentWarm, 4.dp.toPx(), o)
        ObjectType.STAR, ObjectType.DOUBLE_STAR -> {
            val mag = p.magnitude ?: 3.0
            val r = (3.6 - mag * 0.6).coerceIn(1.0, 4.5).toFloat()
            drawCircle(PolarisColors.Starlight, r.dp.toPx(), o)
        }
        ObjectType.GALAXY -> drawOval(Color(0xFFB59CFF), topLeft = o - Offset(5.dp.toPx(), 3.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(10.dp.toPx(), 6.dp.toPx()), style = Stroke(1.2.dp.toPx()))
        ObjectType.NEBULA, ObjectType.PLANETARY_NEBULA -> drawCircle(Color(0xFF6FE3D1), 4.dp.toPx(), o, style = Stroke(1.2.dp.toPx()))
        ObjectType.OPEN_CLUSTER, ObjectType.GLOBULAR_CLUSTER -> drawCircle(Color(0xFF9FC4FF), 4.dp.toPx(), o,
            style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
    }
}

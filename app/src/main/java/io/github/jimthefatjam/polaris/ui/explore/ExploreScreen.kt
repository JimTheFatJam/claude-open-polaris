package io.github.jimthefatjam.polaris.ui.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jimthefatjam.polaris.core.astro.Catalog
import io.github.jimthefatjam.polaris.core.model.ObjectPosition
import io.github.jimthefatjam.polaris.core.model.ObjectType
import io.github.jimthefatjam.polaris.core.model.Visibility
import io.github.jimthefatjam.polaris.data.UserSettings
import io.github.jimthefatjam.polaris.di.AppContainer
import io.github.jimthefatjam.polaris.ui.components.Banner
import io.github.jimthefatjam.polaris.ui.components.Dot
import io.github.jimthefatjam.polaris.ui.components.Formatter
import io.github.jimthefatjam.polaris.ui.components.KeyValue
import io.github.jimthefatjam.polaris.ui.components.QualityBadge
import io.github.jimthefatjam.polaris.ui.components.rememberFormatter
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import io.github.jimthefatjam.polaris.core.model.DataQuality
import kotlin.math.roundToInt

private enum class TypeFilter(val label: String, val match: (ObjectType) -> Boolean) {
    ALL("All", { true }),
    SOLAR("Solar system", { it == ObjectType.MOON || it == ObjectType.PLANET || it == ObjectType.SUN }),
    STARS("Stars", { it == ObjectType.STAR || it == ObjectType.DOUBLE_STAR }),
    CLUSTERS("Clusters", { it == ObjectType.OPEN_CLUSTER || it == ObjectType.GLOBULAR_CLUSTER }),
    NEBULAE("Nebulae", { it == ObjectType.NEBULA || it == ObjectType.PLANETARY_NEBULA }),
    GALAXIES("Galaxies", { it == ObjectType.GALAXY }),
}

@Composable
fun ExploreScreen(container: AppContainer) {
    val obs by container.observing.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(UserSettings())
    val fmt = rememberFormatter(obs.zone, settings.units)
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(TypeFilter.ALL) }
    var visibleOnly by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ObjectPosition?>(null) }

    val byId = obs.sky?.objects?.associateBy { it.obj.id }.orEmpty()
    val results = Catalog.search(query)
        .filter { filter.match(it.type) }
        .mapNotNull { byId[it.id] }
        .filter { !visibleOnly || it.isAboveHorizon }
        .sortedWith(compareByDescending<ObjectPosition> { it.observability }.thenByDescending { it.altitudeDeg })

    detail?.let { ObjectDetailSheet(it, fmt) { detail = null } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Explore", style = MaterialTheme.typography.headlineSmall) }
        item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Search: Saturn, M31, Orion, galaxy…") },
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TypeFilter.entries) { f -> FilterChip(filter == f, { filter = f }, label = { Text(f.label) }) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Only above the horizon", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(visibleOnly, { visibleOnly = it })
            }
        }
        if (obs.sky == null) item { Banner("Set a location on the Tonight screen to see positions and visibility.") }
        else if (results.isEmpty()) item { Text("No matching objects.", color = PolarisColors.Muted) }
        items(results, key = { it.obj.id }) { p -> ObjectListRow(p, fmt) { detail = p } }
    }
}

@Composable
fun ObjectListRow(p: ObjectPosition, fmt: Formatter, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(visibilityColor(p))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.obj.name, style = MaterialTheme.typography.bodyLarge)
                Text(listOfNotNull(p.obj.type.label, p.obj.catalogIds.firstOrNull(), p.obj.constellation).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                Text(fmt.riseSetLine(p.riseSet, p.isAboveHorizon), style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (p.isAboveHorizon) "${p.altitudeDeg.roundToInt()}° ${Formatter.compassPoint(p.azimuthDeg)}" else "Below",
                    style = MaterialTheme.typography.bodyMedium)
                Text(p.visibility.label, style = MaterialTheme.typography.labelSmall, color = visibilityColor(p))
            }
        }
        HorizontalDivider(color = PolarisColors.Outline.copy(alpha = 0.5f))
    }
}

private fun visibilityColor(p: ObjectPosition) = when {
    p.visibility == Visibility.BELOW_HORIZON -> PolarisColors.Faint
    p.visibility == Visibility.DAYTIME || p.visibility == Visibility.LOST_IN_TWILIGHT -> PolarisColors.Poor
    p.observability > 0.6 -> PolarisColors.Excellent
    p.observability > 0.3 -> PolarisColors.Good
    else -> PolarisColors.Fair
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetailSheet(p: ObjectPosition, fmt: Formatter, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PolarisColors.NightElevated) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(p.obj.name, style = MaterialTheme.typography.headlineSmall)
            Text(listOfNotNull(p.obj.type.label, p.obj.catalogIds.joinToString(", ").ifBlank { null }, p.obj.constellation?.let { "in $it" }).joinToString(" · "),
                color = PolarisColors.Muted)
            Spacer(Modifier.height(10.dp))
            if (p.obj.description.isNotBlank()) Text(p.obj.description, style = MaterialTheme.typography.bodyMedium)
            p.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = PolarisColors.AccentWarm) }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Position now (${fmt.time(p.time)})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                QualityBadge(DataQuality.CALCULATED)
            }
            KeyValue("Status", p.visibility.label)
            KeyValue("Altitude", "%.1f°".format(p.altitudeDeg))
            KeyValue("Azimuth", "%.1f° (%s)".format(p.azimuthDeg, Formatter.compassPoint(p.azimuthDeg)))
            KeyValue("Right ascension (of date)", Formatter.ra(p.raHours))
            KeyValue("Declination (of date)", Formatter.dec(p.decDeg))
            p.magnitude?.let { KeyValue("Magnitude", "%.1f".format(it)) }
            p.obj.sizeArcmin?.let { KeyValue("Apparent size", "%.0f′".format(it)) }
            p.illuminatedFraction?.let { KeyValue("Illuminated", "${(it * 100).roundToInt()}%") }
            p.moonSeparationDeg?.let { KeyValue("Distance from Moon", "${it.roundToInt()}°") }
            val rs = p.riseSet
            if (rs != null) {
                Spacer(Modifier.height(8.dp))
                Text("Rise / transit / set", style = MaterialTheme.typography.titleSmall)
                when {
                    rs.alwaysUp -> KeyValue("Visibility", "Circumpolar (never sets)")
                    rs.neverUp -> KeyValue("Visibility", "Never rises at this latitude")
                    else -> {
                        KeyValue("Next rise", fmt.dayTime(rs.rise))
                        KeyValue("Next transit (highest)", fmt.dayTime(rs.transit))
                        KeyValue("Next set", fmt.dayTime(rs.set))
                    }
                }
                if (rs.alwaysUp) KeyValue("Next transit (highest)", fmt.dayTime(rs.transit))
            }
        }
    }
}

package io.github.jimthefatjam.polaris.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jimthefatjam.polaris.BuildConfig
import io.github.jimthefatjam.polaris.core.scoring.Bortle
import io.github.jimthefatjam.polaris.data.LocationMode
import io.github.jimthefatjam.polaris.data.UnitSystem
import io.github.jimthefatjam.polaris.data.UserSettings
import io.github.jimthefatjam.polaris.di.AppContainer
import io.github.jimthefatjam.polaris.ui.components.Formatter
import io.github.jimthefatjam.polaris.ui.components.PolarisCard
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(container: AppContainer) {
    val s by container.settings.settings.collectAsStateWithLifecycle(UserSettings())
    val obs by container.observing.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            if (granted) container.settings.setLocationMode(LocationMode.DEVICE)
            container.observing.refresh(false)
        }
    }

    if (picker) LocationPickerDialog(container.placeSearch, { picker = false }) { loc ->
        scope.launch { container.settings.setManualLocation(loc) }
        picker = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }

        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("Location", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                RadioRow("Use device location (approximate)", s.locationMode == LocationMode.DEVICE) {
                    if (container.location.hasPermission()) scope.launch { container.settings.setLocationMode(LocationMode.DEVICE) }
                    else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                RadioRow("Use a manual location", s.locationMode == LocationMode.MANUAL) {
                    if (s.manualLocation != null) scope.launch { container.settings.setLocationMode(LocationMode.MANUAL) } else picker = true
                }
                s.manualLocation?.let {
                    Text("Manual: ${it.name ?: Formatter.coords(it.latitude, it.longitude)}", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                }
                obs.location?.let {
                    Text("In use: ${Formatter.coords(it.latitude, it.longitude)} · time zone ${obs.zone.id}", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { picker = true }) { Text("Choose manual location…") }
            }
        }

        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("Light pollution (Bortle class)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "No free API provides reliable sky brightness, so tell Polaris how dark your site is. " +
                        "Tip: compare with a light-pollution atlas or how much of the Milky Way you can see.",
                    style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = s.bortle == null, onClick = { scope.launch { container.settings.setBortle(null) } }, label = { Text("Unknown") })
                    Spacer(Modifier.padding(4.dp))
                    Text(s.bortle?.let { "Class $it — ${Bortle.name(it)}" } ?: "Not set", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = (s.bortle ?: 5).toFloat(), valueRange = 1f..9f, steps = 7,
                    onValueChange = { v -> scope.launch { container.settings.setBortle(v.roundToInt()) } },
                )
                s.bortle?.let { Text(Bortle.hint(it), style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint) }
            }
        }

        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("Units", style = MaterialTheme.typography.titleMedium)
                UnitSystem.entries.forEach { u ->
                    RadioRow(u.label, s.units == u) { scope.launch { container.settings.setUnits(u) } }
                }
            }
        }

        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-refresh", style = MaterialTheme.typography.titleMedium)
                        Text("Refresh weather every 5 minutes while the Tonight screen is open (data is cached for 30 minutes and refreshes are rate-limited). When off, only sky positions update.",
                            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                    }
                    Switch(s.autoRefresh, { v -> scope.launch { container.settings.setAutoRefresh(v) } })
                }
            }
        }

        item {
            PolarisCard(Modifier.fillMaxWidth()) {
                Text("About Polaris ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Scores combine show-stoppers (cloud, precipitation, daylight, target below horizon) multiplied with a weighted mean of quality factors " +
                        "(transparency, seeing, wind, dew, moonlight, light pollution). Seeing and transparency are estimates derived from " +
                        "model humidity, visibility and jet-stream wind — no public weather API measures them directly. Each factor is labelled with " +
                        "its data source. Full method: docs/STARGAZING_SCORING.md in the project repository.",
                    style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted,
                )
                Spacer(Modifier.height(8.dp))
                Text("Weather data by Open-Meteo.com (CC BY 4.0) · Astronomy Engine © Don Cross (MIT)", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
                Text("Source: github.com/JimTheFatJam/claude-open-polaris", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

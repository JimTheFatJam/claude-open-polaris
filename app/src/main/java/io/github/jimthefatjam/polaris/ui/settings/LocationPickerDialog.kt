package io.github.jimthefatjam.polaris.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.LocationSource
import io.github.jimthefatjam.polaris.core.weather.Place
import io.github.jimthefatjam.polaris.core.weather.PlaceSearch
import io.github.jimthefatjam.polaris.core.weather.WeatherException
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import kotlinx.coroutines.delay

/** Manual location: search a place name (Open-Meteo geocoding) or type coordinates (works offline). */
@Composable
fun LocationPickerDialog(search: PlaceSearch, onDismiss: () -> Unit, onPicked: (GeoLocation) -> Unit) {
    var mode by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); error = null; return@LaunchedEffect }
        delay(400) // debounce
        loading = true
        error = null
        results = try {
            search.search(query).also { if (it.isEmpty()) error = "No places found" }
        } catch (e: WeatherException) {
            error = if (e is WeatherException.Network) "Offline — enter coordinates instead" else e.message
            emptyList()
        }
        loading = false
    }

    val latV = lat.replace(',', '.').toDoubleOrNull()
    val lonV = lon.replace(',', '.').toDoubleOrNull()
    val coordsValid = latV != null && lonV != null && latV in -90.0..90.0 && lonV in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PolarisColors.NightElevated,
        title = { Text("Choose location") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(mode == 0, { mode = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Search") }
                    SegmentedButton(mode == 1, { mode = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Coordinates") }
                }
                Spacer(Modifier.height(12.dp))
                if (mode == 0) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("City or place") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions())
                    Spacer(Modifier.height(8.dp))
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    error?.let { Text(it, color = PolarisColors.Fair, style = MaterialTheme.typography.bodySmall) }
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(results) { p ->
                            Column(Modifier.fillMaxWidth().clickable {
                                onPicked(GeoLocation(p.latitude, p.longitude, p.elevationMeters ?: 0.0, p.displayName, p.timeZoneId, LocationSource.MANUAL))
                            }.padding(vertical = 10.dp)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                Text(listOfNotNull(p.region, p.country).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                            }
                            HorizontalDivider(color = PolarisColors.Outline)
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(lat, { lat = it }, Modifier.weight(1f), label = { Text("Latitude") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), placeholder = { Text("51.48") })
                        OutlinedTextField(lon, { lon = it }, Modifier.weight(1f), label = { Text("Longitude") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), placeholder = { Text("-0.01") })
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Text("Decimal degrees; south and west are negative. The time zone is taken from the weather service when online, otherwise from this device.",
                        style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
                    if ((lat.isNotEmpty() || lon.isNotEmpty()) && !coordsValid) {
                        Text("Latitude must be −90…90 and longitude −180…180.", color = PolarisColors.Poor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (mode == 1) TextButton(enabled = coordsValid, onClick = {
                onPicked(GeoLocation(latV!!, lonV!!, 0.0, name.ifBlank { null }, null, LocationSource.MANUAL))
            }) { Text("Use these coordinates") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

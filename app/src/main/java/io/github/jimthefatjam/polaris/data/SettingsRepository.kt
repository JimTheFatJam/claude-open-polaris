package io.github.jimthefatjam.polaris.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.LocationSource
import io.github.jimthefatjam.polaris.core.telescope.DeviceAxis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class LocationMode { DEVICE, MANUAL }
enum class UnitSystem(val label: String) { METRIC("Metric (°C, km/h)"), IMPERIAL("Imperial (°F, mph)") }

data class UserSettings(
    val locationMode: LocationMode = LocationMode.DEVICE,
    val manualLocation: GeoLocation? = null,
    val lastDeviceLocation: GeoLocation? = null,
    /** Bortle class 1..9 chosen by the user; null = unknown (flagged as unavailable in scoring). */
    val bortle: Int? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    val autoRefresh: Boolean = true,
    val deviceAxis: DeviceAxis = DeviceAxis.TOP_EDGE,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "polaris_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val mode = stringPreferencesKey("location_mode")
        val mLat = doublePreferencesKey("manual_lat")
        val mLon = doublePreferencesKey("manual_lon")
        val mElev = doublePreferencesKey("manual_elev")
        val mName = stringPreferencesKey("manual_name")
        val mTz = stringPreferencesKey("manual_tz")
        val dLat = doublePreferencesKey("device_lat")
        val dLon = doublePreferencesKey("device_lon")
        val dElev = doublePreferencesKey("device_elev")
        val bortle = intPreferencesKey("bortle")
        val units = stringPreferencesKey("units")
        val autoRefresh = booleanPreferencesKey("auto_refresh")
        val axis = stringPreferencesKey("device_axis")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            locationMode = p[Keys.mode]?.let { runCatching { LocationMode.valueOf(it) }.getOrNull() } ?: LocationMode.DEVICE,
            manualLocation = loc(p[Keys.mLat], p[Keys.mLon], p[Keys.mElev], p[Keys.mName], p[Keys.mTz], LocationSource.MANUAL),
            lastDeviceLocation = loc(p[Keys.dLat], p[Keys.dLon], p[Keys.dElev], null, null, LocationSource.DEVICE),
            bortle = p[Keys.bortle]?.takeIf { it in 1..9 },
            units = p[Keys.units]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.METRIC,
            autoRefresh = p[Keys.autoRefresh] ?: true,
            deviceAxis = p[Keys.axis]?.let { runCatching { DeviceAxis.valueOf(it) }.getOrNull() } ?: DeviceAxis.TOP_EDGE,
        )
    }

    private fun loc(lat: Double?, lon: Double?, elev: Double?, name: String?, tz: String?, src: LocationSource) =
        if (lat != null && lon != null) runCatching { GeoLocation(lat, lon, elev ?: 0.0, name, tz, src) }.getOrNull() else null

    suspend fun setLocationMode(mode: LocationMode) = context.dataStore.edit { it[Keys.mode] = mode.name }

    suspend fun setManualLocation(l: GeoLocation) = context.dataStore.edit {
        it[Keys.mLat] = l.latitude
        it[Keys.mLon] = l.longitude
        it[Keys.mElev] = l.elevationMeters
        val n = l.name; if (n != null) it[Keys.mName] = n else it.remove(Keys.mName)
        val tz = l.timeZoneId; if (tz != null) it[Keys.mTz] = tz else it.remove(Keys.mTz)
        it[Keys.mode] = LocationMode.MANUAL.name
    }

    suspend fun setLastDeviceLocation(l: GeoLocation) = context.dataStore.edit {
        it[Keys.dLat] = l.latitude
        it[Keys.dLon] = l.longitude
        it[Keys.dElev] = l.elevationMeters
    }

    suspend fun setBortle(b: Int?) = context.dataStore.edit { if (b == null) it.remove(Keys.bortle) else it[Keys.bortle] = b.coerceIn(1, 9) }
    suspend fun setUnits(u: UnitSystem) = context.dataStore.edit { it[Keys.units] = u.name }
    suspend fun setAutoRefresh(v: Boolean) = context.dataStore.edit { it[Keys.autoRefresh] = v }
    suspend fun setDeviceAxis(a: DeviceAxis) = context.dataStore.edit { it[Keys.axis] = a.name }
}

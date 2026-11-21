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
import io.github.jimthefatjam.polaris.core.telescope.HeadingSource
import io.github.jimthefatjam.polaris.core.telescope.MountingProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class LocationMode { DEVICE, MANUAL }

/** Telescope pointing model: v0.1.0 fixed-axis mode, or a calibrated mounting profile. */
enum class PointingMode(val label: String) { SIMPLE("Simple"), PROFILE("Mounting profile") }
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
    val pointingMode: PointingMode = PointingMode.SIMPLE,
    val headingSource: HeadingSource = HeadingSource.COMPASS,
    val mountingProfiles: List<MountingProfile> = emptyList(),
    val activeProfileId: String? = null,
) {
    val activeProfile: MountingProfile? get() = mountingProfiles.firstOrNull { it.id == activeProfileId }
}

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
        val pointingMode = stringPreferencesKey("pointing_mode")
        val headingSource = stringPreferencesKey("heading_source")
        val profiles = stringPreferencesKey("mounting_profiles_json")
        val activeProfile = stringPreferencesKey("active_mounting_profile")
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
            pointingMode = p[Keys.pointingMode]?.let { runCatching { PointingMode.valueOf(it) }.getOrNull() } ?: PointingMode.SIMPLE,
            headingSource = p[Keys.headingSource]?.let { runCatching { HeadingSource.valueOf(it) }.getOrNull() } ?: HeadingSource.COMPASS,
            mountingProfiles = MountingProfile.listFromJson(p[Keys.profiles]),
            activeProfileId = p[Keys.activeProfile],
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
    suspend fun setPointingMode(m: PointingMode) = context.dataStore.edit { it[Keys.pointingMode] = m.name }
    suspend fun setHeadingSource(h: HeadingSource) = context.dataStore.edit { it[Keys.headingSource] = h.name }

    /** Adds or replaces a profile (by id) and makes it active. */
    suspend fun saveProfile(profile: MountingProfile) = context.dataStore.edit {
        val list = MountingProfile.listFromJson(it[Keys.profiles]).filterNot { p -> p.id == profile.id } + profile
        it[Keys.profiles] = MountingProfile.listToJson(list)
        it[Keys.activeProfile] = profile.id
        it[Keys.pointingMode] = PointingMode.PROFILE.name
    }

    suspend fun setActiveProfile(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.activeProfile) else it[Keys.activeProfile] = id
    }

    suspend fun deleteProfile(id: String) = context.dataStore.edit {
        val list = MountingProfile.listFromJson(it[Keys.profiles]).filterNot { p -> p.id == id }
        it[Keys.profiles] = MountingProfile.listToJson(list)
        if (it[Keys.activeProfile] == id) it.remove(Keys.activeProfile)
    }
}
